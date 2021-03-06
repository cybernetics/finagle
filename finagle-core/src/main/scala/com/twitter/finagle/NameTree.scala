package com.twitter.finagle

import com.twitter.util.{Activity, Var}
import com.twitter.finagle.util.Showable
import scala.annotation.tailrec
import java.net.{InetSocketAddress, SocketAddress}
import scala.collection.breakOut

/**
 * Name trees represent a composite T-typed name whose interpretation
 * is subject to evaluation rules. Typically, a [[com.twitter.finagle.Namer Namer]]
 * is used to provide evaluation context for these trees.
 *
 *  - [[com.twitter.finagle.NameTree.Union]] nodes represent the union of several
 *  trees; a destination is reached by load-balancing over the sub-trees.
 *
 *  - [[com.twitter.finagle.NameTree.Alt Alt]] nodes represent a fail-over relationship
 *  between several trees; the first successful tree is picked as the destination. When
 *  the tree-list is empty, Alt-nodes evaluate to Empty.
 *
 *  - A [[com.twitter.finagle.NameTree.Leaf Leaf]] represents a T-typed leaf node;
 *
 *  - A [[com.twitter.finagle.NameTree.Neg Neg]] represents a negative location; no
 *  destination exists here.
 *
 *  - Finally, [[com.twitter.finagle.NameTree.Empty Empty]] trees represent an empty
 *  location: it exists but is uninhabited at this time.
 */
sealed trait NameTree[+T] {
  /**
   * Use `f` to map a T-typed NameTree to a U-typed one.
   */
  def map[U](f: T => U): NameTree[U] =
    NameTree.map(f)(this)

   /**
    * A parsable representation of the name tree; a
    * [[com.twitter.finagle.NameTree NameTree]] is recovered
    * from this string by
    * [[com.twitter.finagle.NameTree.read NameTree.read]].
    */
  def show(implicit showable: Showable[T]): String = NameTree.show(this)

  /**
   * A simplified version of this NameTree -- the returned
   * name tree is equivalent vis-à-vis evaluation. The returned
   * name also represents a fixpoint; in other words:
   *
   * {{{
   *   tree.simplified == tree.simplified.simplified
   * }}}
   */
  def simplified: NameTree[T] = NameTree.simplify(this)

  /**
   * Evaluate this NameTree with the default evaluation strategy. A
   * tree is evaluated recursively, Alt nodes are evaluated by
   * selecting its first nonnegative child.
   */
  def eval[U>:T]: Option[Set[U]] = NameTree.eval[U](this) match {
    case NameTree.Fail => None
    case NameTree.Neg => None
    case NameTree.Leaf(value) => Some(value)
    case _ => scala.sys.error("bug")
  }
}

/**
 * The NameTree object comprises
 * [[com.twitter.finagle.NameTree NameTree]] types as well
 * as binding and evaluation routines.
 */
object NameTree {
  /**
   * A [[com.twitter.finagle.NameTree NameTree]] representing
   * fallback; it is evaluated by picking the first nonnegative
   * (evaluated) subtree.
   */
  case class Alt[+T](trees: NameTree[T]*) extends NameTree[T] {
    override def toString = "Alt(%s)".format(trees mkString ",")
  }
  object Alt {
    private[finagle] def fromSeq[T](trees: Seq[NameTree[T]]): Alt[T] = Alt(trees:_*)
  }

  /**
   * A [[com.twitter.finagle.NameTree NameTree]] representing a union
   * of trees. It is evaluated by returning the union of atoms of its
   * (recursively evaluated) children. When all children are negative,
   * the Union itself evaluates negative.
   */
  case class Union[+T](trees: NameTree[T]*) extends NameTree[T] {
    override def toString = "Union(%s)".format(trees mkString ",")
  }
  object Union {
    private[finagle] def fromSeq[T](trees: Seq[NameTree[T]]): Union[T] = Union(trees:_*)
  }

  case class Leaf[+T](value: T) extends NameTree[T]

  /**
    * A failing [[com.twitter.finagle.NameTree NameTree]].
    */
  object Fail extends NameTree[Nothing] {
    override def toString = "Fail"
  }

  /**
   * A negative [[com.twitter.finagle.NameTree NameTree]].
   */
  object Neg extends NameTree[Nothing] {
    override def toString = "Neg"
  }

  /**
   * An empty [[com.twitter.finagle.NameTree NameTree]].
   */
  object Empty extends NameTree[Nothing] {
    override def toString = "Empty"
  }

  /**
   * Rewrite the paths in a tree for values defined by the given
   * partial function.
   */
  def map[T, U](f: T => U)(tree: NameTree[T]): NameTree[U] =
    tree match {
      case Union(trees@_*) =>
        val trees1 = trees map map(f)
        Union(trees1:_*)

      case Alt(trees@_*) =>
        val trees1 = trees map map(f)
        Alt(trees1:_*)

      case Leaf(t) => Leaf(f(t))

      case Fail => Fail
      case Neg => Neg
      case Empty => Empty
    }

  private[this] def simplifyTrees[T](
    trees: Seq[NameTree[T]],
    construct: Seq[NameTree[T]] => NameTree[T],
    fail: Seq[NameTree[T]] => Seq[NameTree[T]]
  ): NameTree[T] = {
    @tailrec def loop(trees: Seq[NameTree[T]], accum: Seq[NameTree[T]]): Seq[NameTree[T]] =
      trees match {
        case Nil => accum
        case Seq(head, tail@_*) =>
          simplify(head) match {
            case Fail => fail(accum)
            case Neg => loop(tail, accum)
            case head => loop(tail, accum :+ head)
          }
      }
    loop(trees, Nil) match {
      case Nil => Neg
      case Seq(head) => head
      case trees => construct(trees)
    }
  }

  /**
   * Simplify the given [[com.twitter.finagle.NameTree NameTree]],
   * yielding a new [[com.twitter.finagle.NameTree NameTree]] which
   * is evaluation-equivalent.
   */
  def simplify[T](tree: NameTree[T]): NameTree[T] = tree match {
    case Alt() => Neg
    case Alt(tree) => simplify(tree)
    case Alt(trees@_*) =>
      simplifyTrees(trees, Alt.fromSeq[T], { accum: Seq[NameTree[T]] => accum :+ Fail })

    case Union() => Neg
    case Union(tree) => simplify(tree)
    case Union(trees@_*) =>
      simplifyTrees(trees, Union.fromSeq[T], { accum: Seq[NameTree[T]] => Seq(Fail) })

    case other => other
  }

  /**
   * A string parseable by [[com.twitter.finagle.NameTree.read NameTree.read]].
   */
  def show[T: Showable](tree: NameTree[T]): String = show1(0)(tree)

  private def show1[T: Showable](level: Int)(name: NameTree[T]): String = name match {
  /* case Weighted(weight, name) => "%.02f*(%s)".format(weight, show(name, level+1)) */
    case Union(tree) =>
      show1(level)(tree)

    case Alt(tree) =>
      show1(level)(tree)

    case Union(trees@_*) =>
      val trees1 = trees map show1(level+1)
      trees1 mkString " & "

    case Alt(trees@_*) if level == 0 =>
      val trees1 = trees map show1(level+1)
      trees1 mkString " | "

    case Alt(trees@_*) =>
      val trees1 = trees map show1(level+1)
      "("+(trees1 mkString " | ")+")"

    case Leaf(l) => Showable.show(l)

    case Fail => "!"
    case Neg => "~"
    case Empty => "$"
  }

  // return value is restricted to Fail | Neg | Leaf
  private def eval[T](tree: NameTree[T]): NameTree[Set[T]] = tree match {
    case Union() | Alt() => Neg
    case Alt(tree) => eval(tree)
    case Union(tree) => eval(tree)
    case Fail => Fail
    case Neg => Neg
    case Empty => Leaf(Set.empty)
    case Leaf(t) => Leaf(Set(t))

    case Union(trees@_*) =>
      @tailrec def loop(trees: Seq[NameTree[T]], accum: Seq[Set[T]]): NameTree[Set[T]] =
        trees match {
          case Nil =>
            accum match {
              case Nil => Neg
              case _ => Leaf(accum.flatten.toSet)
            }
          case Seq(head, tail@_*) =>
            eval(head) match {
              case Fail => Fail
              case Neg => loop(tail, accum)
              case Leaf(value) => loop(tail, accum :+ value)
              case _ => scala.sys.error("bug")
            }
        }
      loop(trees, Nil)

    case Alt(trees@_*) =>
      @tailrec def loop(trees: Seq[NameTree[T]]): NameTree[Set[T]] =
        trees match {
          case Nil => Neg
          case Seq(head, tail@_*) =>
            eval(head) match {
              case Fail => Fail
              case Neg => loop(tail)
              case head@Leaf(_) => head
              case _ => scala.sys.error("bug")
            }
        }
      loop(trees)
  }

  implicit def equiv[T]: Equiv[NameTree[T]] = new Equiv[NameTree[T]] {
    def equiv(t1: NameTree[T], t2: NameTree[T]): Boolean =
      simplify(t1) == simplify(t2)
  }

  /**
   * Parse a [[com.twitter.finagle.NameTree NameTree]] from a string
   * with concrete syntax
   *
   * {{{
   * tree       ::= name
   *                weight '*' tree
   *                tree '&' tree
   *                tree '|' tree
   *                '(' tree ')'
   *
   * name       ::= path | '!' | '~' | '$'
   *
   * weight     ::= -?([0-9]++(\.[0-9]+*)?|[0-9]+*\.[0-9]++)([eE][+-]?[0-9]++)?[fFdD]?
   * }}}
   *
   * For example:
   *
   * {{{
   * /foo & /bar | /baz | $
   * }}}
   *
   * parses in to the [[com.twitter.finagle.NameTree NameTree]]
   *
   * {{{
   * Alt(Union(Leaf(Path(foo)),Leaf(Path(bar))),Leaf(Path(baz)),Empty)
   * }}}
   *
   * The production ``path`` is documented at [[com.twitter.finagle.Path$ Path.read]].
   *
   * @throws IllegalArgumentException when the string does not
   * represent a valid name tree.
   */
  def read(s: String): NameTree[Path] = NameTreeParsers.parseNameTree(s)
}
