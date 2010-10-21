package com.twitter.finagle.thrift

import org.specs.Specification
import org.specs.matcher.Matcher

import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}

import org.apache.thrift.{TBase, TApplicationException}
import org.apache.thrift.protocol.{
  TProtocol, TBinaryProtocol, TMessage, TMessageType}

import com.twitter.finagle.SunkChannel
import com.twitter.silly.Silly

import ChannelBufferConversions._

object ThriftCodecSpec extends Specification {
  case class matchExceptionEvent(exc: Throwable) extends Matcher[ChannelEvent]() {
    def apply(event: => ChannelEvent) =
      event match {
        case excEvent: ExceptionEvent =>
          val cause = excEvent.getCause
          if (cause.getClass != exc.getClass)
            (false, "", "wrong exception class %s".format(excEvent.getCause.getClass))
          else if (cause.getMessage != exc.getMessage)
            (false, "", "wrong exception message %s".format(cause.getMessage))
          else
            (true, "throws the right exception", "")

        case _ =>
          (false, "", "not an exception event")
      }
  }

  case class haveType[T <: AnyRef]() extends Matcher[AnyRef]() {
    def apply(obj: => AnyRef) =
      (obj.isInstanceOf[T], "is correct type", "has incorrect type %s".format(obj.getClass))
  }

  case class withType[T <: AnyRef](f: T => Boolean) extends Matcher[AnyRef]() {
    def apply(obj: => AnyRef) =
      obj match {
        case t: T =>
          (f(t), "passed test", "failed test")
        case _ =>
          (false, "", "has incorrect type")
      }
  }

  object TMessage {
    def apply(
      method: String, `type`: Byte, seqid: Int,
      message: { def write(p: TProtocol) }): ChannelBuffer =
    {
      val buf = ChannelBuffers.dynamicBuffer()
      val oprot = new TBinaryProtocol(buf, true, true)
      oprot.writeMessageBegin(new TMessage(method, `type`, seqid))
      message.write(oprot)
      oprot.writeMessageEnd()
      buf
    }
  }

  def makeChannel = SunkChannel {
    val pipeline = Channels.pipeline()
    pipeline.addLast("encoder", new ThriftCodec)
    pipeline
  }

  "request serialization" should {
    "produce valid thrift serialization" in {
      val ch = makeChannel
      Channels.write(ch, ThriftCall("testMethod", new Silly.bleep_args("the arg")))

      ch.upstreamEvents must haveSize(0)
      ch.downstreamEvents must haveSize(1)
  
      ch.downstreamEvents(0) must haveType[MessageEvent]
      val m = ch.downstreamEvents(0).asInstanceOf[MessageEvent].getMessage()
      m must haveType[ChannelBuffer]
      val buf = m.asInstanceOf[ChannelBuffer]
      
      val iprot = new TBinaryProtocol(buf, true, true)
      val msg = iprot.readMessageBegin()

      msg.`type` must be_==(TMessageType.CALL)
      msg.name must be_==("testMethod")

      val args = new Silly.bleep_args()
      args.read(iprot)
      
      args.request must be_==("the arg")
    }

    "serialize exceptions" in {
      val ch = makeChannel

      val exc = new TApplicationException(
        TApplicationException.INTERNAL_ERROR,
        "arbitary exception")

      // We need to write a call to the channel to set the
      // ``currentCall''
      Channels.write(ch, ThriftCall("testMethod", new Silly.bleep_args("the arg")))

      // Reply
      Channels.fireMessageReceived(
        ch, TMessage("testMethod", TMessageType.EXCEPTION, 1, exc))

      ch.downstreamEvents must haveSize(1)
      ch.upstreamEvents must haveSize(1)

      ch.upstreamEvents(0) must matchExceptionEvent(exc)
    }

    "keep track of sequence #s" in {
      val ch = makeChannel

      Channels.write(ch, ThriftCall("testMethod", new Silly.bleep_args("some arg")))

      ch.upstreamEvents must beEmpty
      ch.downstreamEvents must haveSize(1)
      ch.downstreamEvents(0) must haveType[MessageEvent]
      val buf = {
        val m = ch.downstreamEvents(0).asInstanceOf[MessageEvent].getMessage()
        m must haveType[ChannelBuffer]
        m.asInstanceOf[ChannelBuffer]
      }

      val iprot = new TBinaryProtocol(buf, true, true)
      val msg = iprot.readMessageBegin()

      msg.`type` must be_==(TMessageType.CALL)
      msg.name must be_==("testMethod")
      msg.seqid must be_==(1)  // Just established.

      // Ok. Make an invalid reply.
      val reply = TMessage(
        "testMethod", TMessageType.REPLY, 2,
        new Silly.bleep_result("grr"))
      Channels.fireMessageReceived(ch, reply)

      ch.upstreamEvents must haveSize(1)
      ch.upstreamEvents(0) must matchExceptionEvent(
        new TApplicationException(
          TApplicationException.BAD_SEQUENCE_ID,
          "out of sequence response"))

      // Additionally, the channel is closed by the codec.
      ch.downstreamEvents must haveSize(2)
      ch.downstreamEvents(1) must withType[ChannelStateEvent] { cse =>
        (cse.getState == ChannelState.OPEN) &&
        (cse.getValue eq java.lang.Boolean.FALSE)
      }
    }

    "handle only one request at a time" in {
      val ch = makeChannel

      // Make one call.
      Channels.write(ch, ThriftCall("testMethod", new Silly.bleep_args("some arg")))
      ch.downstreamEvents must haveSize(1)
      
      // Try another before replying.
      val f = Channels.write(ch, ThriftCall("testMethod", new Silly.bleep_args("some arg")))
      ch.downstreamEvents must haveSize(1)
      ch.upstreamEvents must haveSize(1)
      ch.upstreamEvents(0) must matchExceptionEvent(
        new Exception("There may be only one outstanding Thrift call at a time"))

      // The future also fails:
      f.isSuccess must beFalse
    }
  }

  "message serializaton" should {

    "throw exceptions on unrecognized request types" in {
      val ch = makeChannel
      Channels.write(ch, "grr")

      ch.downstreamEvents must haveSize(0)
      ch.upstreamEvents must haveSize(1)
      ch.upstreamEvents(0) must matchExceptionEvent(
        new IllegalArgumentException("Unrecognized request type"))
    }
  }
}