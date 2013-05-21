trait TList {
  def head: Int
  def tail:TList
  def isEmpty:Boolean
  def toStr(acc:StringBuilder = new StringBuilder):String
  override def toString = "TList("+toStr()+")"
}

case object TNil extends TList {
  def head = throw new java.util.NoSuchElementException("head of EmptyList")
  def tail = throw new java.util.NoSuchElementException("tail of EmptyList")
  def isEmpty = true

  def toStr(acc: StringBuilder): String = acc.toString()
}

case class Cons(head: Int, tail: TList) extends TList {
  def toStr(acc:StringBuilder):String = {
    if (acc.length > 0) tail.toStr(acc append ", " append head)
    else tail.toStr(acc append head)
  }
  def isEmpty = false
}

trait T {
  def filter(p: Int => Boolean):T = filterAcc(p, new E)
  def filterAcc(p: Int => Boolean, acc:T):T
  def contains(other_elem: Int): Boolean
  def incl(elem: Int): T
  def remove(elem: Int): T
  def toStrList(row:Int = 0, pos:Int = 0, offset:Int = 0, acc:List[(String, Int, Int, Int)] = List()):List[(String, Int, Int, Int)]
  def toList(acc:List[Int] = List()):List[Int]
  def toTList(acc:TList = TNil):TList

  /*def union(other_t:T):T = {
    other_t.toList().foldLeft[T](toList().foldLeft[T](new E){case (res, t) => res.incl(t)}) {
      case (res, t) => res.incl(t)
    }
  }*/

  def union(that:T):T = {
    def _pew(acc:T, tl:TList):T = {
      tl match {
        case Cons(head, tail) =>
          _pew(acc.incl(head), tail)
        case TNil => acc
      }
    }
    _pew(this, that.toTList())
  }

  def findMax:Int
  def sortedDown:TList
}

class N(val elem:Int, val left:T, val right:T) extends T {
  def filterAcc(p: Int => Boolean, acc:T):T = {
    (if(p(elem)) acc.incl(elem) else acc).union(left.filter(p)).union(right.filter(p))
  }

  def contains(other_elem: Int): Boolean = {
    if(other_elem < elem) left.contains(other_elem)
    else if(other_elem > elem) right.contains(other_elem)
    else true
  }
  def incl(other_elem: Int): T = {
    if(other_elem < elem) new N(elem, left.incl(other_elem), right)
    else if(other_elem > elem) new N(elem, left, right.incl(other_elem))
    else this
  }
  def remove(other_elem: Int): T = {
    if(other_elem < elem) new N(elem, left.remove(other_elem), right)
    else if(other_elem > elem) new N(elem, left, right.remove(other_elem))
    else left.union(right)
  }

  def toStrList(row:Int = 0, pos:Int = 0, offset:Int = 0, acc:List[(String, Int, Int, Int)] = List()):List[(String, Int, Int, Int)] = {
    right.toStrList(row+1, pos+1, offset = -1, left.toStrList(row+1, pos-1, offset = 1, (elem.toString, row, pos, offset) :: acc))
  }

  def toList(acc:List[Int] = List()):List[Int] = {
    right.toList(left.toList((elem :: acc)))
  }

  override def toString = {
    val tree = toStrList()
    val (min_pos, max_pos, max_height) = tree.foldLeft((0, 0, 0)) {
      case ((minp, maxp, maxh), t) => (if (t._3 < minp) t._3 else minp, if (t._3 > maxp) t._3 else maxp, if (t._2 > maxh) t._2 else maxh)
    }
    val width = max_pos - min_pos + 1
    val height = max_height + 1
    val arr = Array.fill(height, width)(new StringBuilder)
    tree.foreach {
      case (e, row, pos, offset) =>
        val s = arr(row)(pos - min_pos)
        if (s.length == 0) s append e
        else {
          if (offset == -1) s insert (0, e+" ") else s append " " append e
        }
    }
    val max_len = (for {
      row <- arr
      pos <- row
    } yield pos.length).max
    for {
      row <- arr
      pos <- row
      if pos.length < max_len
    } {
      var to_end = true
      while(pos.length < max_len) {
        if (to_end )pos append " " else pos insert (0, " ")
        to_end = !to_end
      }
    }
    val result = new StringBuilder
    arr.foreach(row => result append (row.mkString) append "\n")
    result.toString()
  }

  def toTList(acc: TList = TNil): TList = {
    right.toTList(left.toTList(new Cons(elem, acc)))
  }

  def findMax: Int = {
    def _pew(res:Int, tl:TList):Int = {
      tl match {
        case Cons(head, tail) =>
          _pew(if(head > res) head else res, tail)
        case TNil => res
      }
    }
    _pew(elem, toTList())
  }

  def sortedDown:TList = {
    val m = findMax
    new Cons(m, remove(m).sortedDown)
  }
}

class E extends T {
  def filterAcc(p: Int => Boolean, acc:T):T = acc
  def contains(elem: Int): Boolean = false
  def incl(elem: Int): T = new N(elem, new E, new E)
  def remove(elem: Int): T = new E
  def toStrList(row:Int = 0, pos:Int = 0, offset:Int = 0, acc:List[(String, Int, Int, Int)] = List()):List[(String, Int, Int, Int)] = {
    ("E", row, pos, offset) :: acc
  }
  def toList(acc: List[Int] = List()): List[Int] = acc

  def toTList(acc: TList = TNil): TList = acc

  def findMax: Int = throw new java.util.NoSuchElementException()

  def sortedDown: TList = TNil

  override def toString = "E"
}

val n1 = (1 to 5).foldLeft[T](new E) {case (t, x) => t.incl((math.random*100).toInt)}
val n2 = (1 to 5).foldLeft[T](new E) {case (t, x) => t.incl((math.random*100).toInt)}
val n3 = n1.union(n2)
println("n1\n"+n1)
println("n2\n"+n2)
println("n1.union(n2)\n"+n1.union(n2))
println("===========================================================")
println("n1.union(n2).findMax() : "+n3.findMax)
println(n3.findMax == n3.toList().max)
println("===========================================================")
println(n3.toTList())
println(n3.sortedDown)
println("===========================================================")
n1.toList().foreach(x => println(x+" : "+n3.contains(x)))
n2.toList().foreach(x => println(x+" : "+n3.contains(x)))
println("-500 : "+n3.contains(-500))
println("===========================================================")
println("n1.union(n2).filter(i => i % 2 == 0)\n"+n3.filter(i => i % 2 == 0))
println("===========================================================")
n1.toList().foreach(x => println(x+"\n"+n3.remove(x)+"\n"+n3.remove(x).contains(x)))
println("===========================================================")
n2.toList().foreach(x => println(x+"\n"+n3.remove(x)+"\n"+n3.remove(x).contains(x)))

val n4 = new N(5, new N(3, new E, new E), new N(6, new E, new E))
println(n1)
println()
val n5 = n4.incl(4)
println(n5.toString)
