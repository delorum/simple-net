package com.github.dunnololda.simplenet

/**
 * Huffman codec from Coursera Scala Assignment 4
 *
 */
object Huffman {

  /**
   * A huffman code is represented by a binary tree.
   *
   * Every `Leaf` node of the tree represents one character of the alphabet that the tree can encode.
   * The weight of a `Leaf` is the frequency of appearance of the character.
   *
   * The branches of the huffman tree, the `Fork` nodes, represent a set containing all the characters
   * present in the leaves below it. The weight of a `Fork` node is the sum of the weights of these
   * leaves.
   */
  abstract class CodeTree
  case class Fork(left: CodeTree, right: CodeTree, chars: List[Char], weight: Int) extends CodeTree
  case class Leaf(char: Char, weight: Int) extends CodeTree



  // Part 1: Basics

  def weight(tree: CodeTree): Int = tree match {
    case Fork(_, _, _, weight) => weight
    case Leaf(_, weight) => weight
  }

  def chars(tree: CodeTree): List[Char] = tree match {
    case Fork(_, _, chars, _) => chars
    case Leaf(char, _) => List(char)
  }

  def makeCodeTree(left: CodeTree, right: CodeTree) =
    Fork(left, right, chars(left) ::: chars(right), weight(left) + weight(right))



  // Part 2: Generating Huffman trees

  /**
   * In this assignment, we are working with lists of characters. This function allows
   * you to easily create a character list from a given string.
   */
  def string2Chars(str: String): List[Char] = str.toList

  /**
   * This function computes for each unique character in the list `chars` the number of
   * times it occurs. For example, the invocation
   *
   *   times(List('a', 'b', 'a'))
   *
   * should return the following (the order of the resulting list is not important):
   *
   *   List(('a', 2), ('b', 1))
   *
   * The type `List[(Char, Int)]` denotes a list of pairs, where each pair consists of a
   * character and an integer. Pairs can be constructed easily using parentheses:
   *
   *   val pair: (Char, Int) = ('c', 1)
   *
   * In order to access the two elements of a pair, you can use the accessors `_1` and `_2`:
   *
   *   val theChar = pair._1
   *   val theInt  = pair._2
   *
   * Another way to deconstruct a pair is using pattern matching:
   *
   *   pair match {
   *     case (theChar, theInt) =>
   *       println("character is: "+ theChar)
   *       println("integer is  : "+ theInt)
   *   }
   */
  def times(chars: Seq[Char]): List[(Char, Int)] = chars.distinct.zip(chars.distinct.map(c => chars.count(x => x == c))).toList

  /**
   * Returns a list of `Leaf` nodes for a given frequency table `freqs`.
   *
   * The returned list should be ordered by ascending weights (i.e. the
   * head of the list should have the smallest weight), where the weight
   * of a leaf is the frequency of the character.
   */
  def makeOrderedLeafList(freqs: Seq[(Char, Int)]): List[Leaf] = freqs.sortBy(_._2).map(x => Leaf(x._1, x._2)).toList

  /**
   * Checks whether the list `trees` contains only one single code tree.
   */
  def singleton(trees: List[CodeTree]): Boolean = trees match {
    case head :: Nil => true
    case _ => false
  }

  /**
   * The parameter `trees` of this function is a list of code trees ordered
   * by ascending weights.
   *
   * This function takes the first two elements of the list `trees` and combines
   * them into a single `Fork` node. This node is then added back into the
   * remaining elements of `trees` at a position such that the ordering by weights
   * is preserved.
   *
   * If `trees` is a list of less than two elements, that list should be returned
   * unchanged.
   */
  def combine(trees: List[CodeTree]): List[CodeTree] = trees match {
    case one :: two :: tail =>
      (makeCodeTree(one, two) :: tail).sortBy(t => weight(t))
    case _ => trees.sortBy(t => weight(t))
  }

  /**
   * This function will be called in the following way:
   *
   *   until(singleton, combine)(trees)
   *
   * where `trees` is of type `List[CodeTree]`, `singleton` and `combine` refer to
   * the two functions defined above.
   *
   * In such an invocation, `until` should call the two functions until the list of
   * code trees contains only one single tree, and then return that singleton list.
   *
   * Hint: before writing the implementation,
   *  - start by defining the parameter types such that the above example invocation
   *    is valid. The parameter types of `until` should match the argument types of
   *    the example invocation. Also define the return type of the `until` function.
   *  - try to find sensible parameter names for `xxx`, `yyy` and `zzz`.
   */
  def until(singleton: List[CodeTree] => Boolean, combine: List[CodeTree] => List[CodeTree])(trees: List[CodeTree]): CodeTree = {
    if(singleton(trees)) trees.head
    else {
      until(singleton, combine)(combine(trees))
    }
  }

  /**
   * This function creates a code tree which is optimal to encode the text `chars`.
   *
   * The parameter `chars` is an arbitrary text. This function extracts the character
   * frequencies from that text and creates a code tree based on them.
   */
  def createCodeTree(chars: List[Char]): CodeTree = {
    //println("creating tree from chars: "+chars.mkString)
    val trees = makeOrderedLeafList(times(chars))
    until(singleton, combine)(trees)
  }

  def createCodeTree(char_freqs: Map[Char, Int]): CodeTree = {
    val trees = makeOrderedLeafList(char_freqs.toSeq)
    until(singleton, combine)(trees)
  }


  // Part 3: Decoding

  type Bit = Int

  /**
   * This function decodes the bit sequence `bits` using the code tree `tree` and returns
   * the resulting list of characters.
   */
  def decode(tree: CodeTree, bits: Seq[Bit], delimiter:Char):Option[List[Char]] = {
    def _decode(_tree:CodeTree, _bits:List[Bit], acc:List[Char]):Option[List[Char]] = {
      _tree match {
        case Fork(left, right, chars, weight) =>
          _bits match {
            case Nil => None
            case head :: tail => head match {
              case 0 => _decode(left, tail, acc)
              case 1 => _decode(right, tail, acc)
            }
          }
        case Leaf(char, weight) =>
          if(char == delimiter) Some(char :: acc)
          else _decode(tree, _bits, char :: acc)
      }
    }
    _decode(tree, bits.toList, List()).map(_.reverse)
  }

  /**
   * A Huffman coding tree for the French language.
   * Generated from the data given at
   *   http://fr.wikipedia.org/wiki/Fr%C3%A9quence_d%27apparition_des_lettres_en_fran%C3%A7ais
   */
  val frenchCode: CodeTree = Fork(Fork(Fork(Leaf('s',121895),Fork(Leaf('d',56269),Fork(Fork(Fork(Leaf('x',5928),Leaf('j',8351),List('x','j'),14279),Leaf('f',16351),List('x','j','f'),30630),Fork(Fork(Fork(Fork(Leaf('z',2093),Fork(Leaf('k',745),Leaf('w',1747),List('k','w'),2492),List('z','k','w'),4585),Leaf('y',4725),List('z','k','w','y'),9310),Leaf('h',11298),List('z','k','w','y','h'),20608),Leaf('q',20889),List('z','k','w','y','h','q'),41497),List('x','j','f','z','k','w','y','h','q'),72127),List('d','x','j','f','z','k','w','y','h','q'),128396),List('s','d','x','j','f','z','k','w','y','h','q'),250291),Fork(Fork(Leaf('o',82762),Leaf('l',83668),List('o','l'),166430),Fork(Fork(Leaf('m',45521),Leaf('p',46335),List('m','p'),91856),Leaf('u',96785),List('m','p','u'),188641),List('o','l','m','p','u'),355071),List('s','d','x','j','f','z','k','w','y','h','q','o','l','m','p','u'),605362),Fork(Fork(Fork(Leaf('r',100500),Fork(Leaf('c',50003),Fork(Leaf('v',24975),Fork(Leaf('g',13288),Leaf('b',13822),List('g','b'),27110),List('v','g','b'),52085),List('c','v','g','b'),102088),List('r','c','v','g','b'),202588),Fork(Leaf('n',108812),Leaf('t',111103),List('n','t'),219915),List('r','c','v','g','b','n','t'),422503),Fork(Leaf('e',225947),Fork(Leaf('i',115465),Leaf('a',117110),List('i','a'),232575),List('e','i','a'),458522),List('r','c','v','g','b','n','t','e','i','a'),881025),List('s','d','x','j','f','z','k','w','y','h','q','o','l','m','p','u','r','c','v','g','b','n','t','e','i','a'),1486387)

  /**
   * What does the secret message say? Can you decode it?
   * For the decoding use the 'frenchCode' Huffman tree defined above.
   */
  val secret: List[Bit] = List(0,0,1,1,1,0,1,0,1,1,1,0,0,1,1,0,1,0,0,1,1,0,1,0,1,1,0,0,1,1,1,1,1,0,1,0,1,1,0,0,0,0,1,0,1,1,1,0,0,1,0,0,1,0,0,0,1,0,0,0,1,0,1)

  // Part 4a: Encoding using Huffman tree

  /**
   * This function encodes `text` using the code tree `tree`
   * into a sequence of bits.
   */
  def encode(tree: CodeTree)(text: List[Char]): List[Bit] = {
    //println("encoding text: "+text.mkString+"\n"+"with tree: "+tree)
    def findChar(char:Char, t:CodeTree, acc:List[Bit]):List[Bit]= {
      t match {
        case Leaf(ch, w) => if(char == ch) acc else List()
        case Fork(l, r, chs, w) =>
          if(chars(l).contains(char))      findChar(char, l, 0 :: acc)
          else if(chars(r).contains(char)) findChar(char, r, 1 :: acc)
          else List()
      }
    }
    def _encode(_text:List[Char], acc:List[Bit]):List[Bit] = {
      _text match {
        case Nil => acc
        case head :: tail =>
          _encode(tail, findChar(head, tree, List()) ::: acc)
      }
    }
    _encode(text, List()).reverse
  }


  // Part 4b: Encoding using code table

  type CodeTable = Map[Char, List[Bit]]

  /**
   * This function returns the bit sequence that represents the character `char` in
   * the code table `table`.
   */
  def codeBits(table: CodeTable)(char: Char): List[Bit] = table.find {
    case (ch, l) => ch == char
  }.map(_._2).getOrElse(List())

  /**
   * Given a code tree, create a code table which contains, for every character in the
   * code tree, the sequence of bits representing that character.
   *
   * Hint: think of a recursive solution: every sub-tree of the code tree `tree` is itself
   * a valid code tree that can be represented as a code table. Using the code tables of the
   * sub-trees, think of how to build the code table for the entire tree.
   */
  def convert(tree: CodeTree): CodeTable = {
    def convert2(_tree: CodeTree, acc:List[Bit]): CodeTable = _tree match {
      case Fork(left, right, chars, weight) =>
        mergeCodeTables(convert2(left, 0 :: acc), convert2(right, 1 :: acc))
      case Leaf(char, weight) => Map(char -> acc.reverse)
    }
    convert2(tree, List())
  }

  /**
   * This function takes two code tables and merges them into one. Depending on how you
   * use it in the `convert` method above, this merge method might also do some transformations
   * on the two parameter code tables.
   */
  def mergeCodeTables(a: CodeTable, b: CodeTable): CodeTable = {
    (a.keys.toList ::: b.keys.toList).distinct.distinct.map(char => {
      (codeBits(a)(char), codeBits(b)(char)) match {
        case (Nil, lb) => (char, lb)
        case (la, Nil) => (char, la)
        case (la, lb) => if(la.length < lb.length) (char, la) else (char, lb)
      }
    }).toMap
  }

  /**
   * This function encodes `text` according to the code tree `tree`.
   *
   * To speed up the encoding process, it first converts the code tree to a code table
   * and then uses it to perform the actual encoding.
   */
  def quickEncode(tree: CodeTree)(text: List[Char]): List[Bit] = {
    val table = convert(tree)
    text.flatMap(char => codeBits(table)(char))
  }

  def dumpCodeTableToFile(filename:String, char_freqs:Map[Char, Int]) {
    val code_tree = createCodeTree(char_freqs)
    val code_table = Huffman.convert(code_tree)
    val fos = new java.io.FileOutputStream(filename)
    for {
      (char, bits) <- code_table.toList.sortBy(_._2.length)
    } {
      fos.write(s"$char : ${bits.mkString(" ")}\n".getBytes)
    }
    fos.close()
  }

  def dumpCharFreqsToFile(filename:String, char_freqs:Map[Char, Int]) {
    val fos = new java.io.FileOutputStream(filename)
    for {
      (char, freq) <- char_freqs.toList.sortBy(-_._2)
    } {
      fos.write(s"$char : $freq\n".getBytes)
    }
    fos.close()
  }

  def dumpCodeTableAsScalaToFile(filename:String, char_freqs:Map[Char, Int]) {
    val code_tree = createCodeTree(char_freqs)
    val code_table = Huffman.convert(code_tree)
    val fos = new java.io.FileOutputStream(filename)
    fos.write("Map(\n".getBytes)
    val code_table_list = code_table.toList
    val code_table_list_len = code_table_list.length
    for {
      ((char, bits), idx) <- code_table_list.sortBy(_._2.length).zipWithIndex
      last_comma = idx < code_table_list_len-1
    } {
      fos.write(s"  '$char' -> ${bits.mkString("List(", ", ", ")")}${if(last_comma)"," else ""}\n".getBytes)
    }
    fos.write(")".getBytes)
    fos.close()
  }

  def dumpCodeTreeAsScalaToFile(filename:String, char_freqs:Map[Char, Int]) {
    val code_tree = createCodeTree(char_freqs)
    val fos = new java.io.FileOutputStream(filename)
    fos.write(code_tree.toString.getBytes)
    fos.close()
  }

  def dumpCharFreqsAsScalaToFile(filename:String, char_freqs:Map[Char, Int]) {
    val fos = new java.io.FileOutputStream(filename)
    fos.write("Map(\n".getBytes)
    val char_freqs_list = char_freqs.toList
    val char_freqs_list_len = char_freqs_list.length
    for {
      ((char, freq), idx) <- char_freqs_list.sortBy(-_._2).zipWithIndex
      last_comma = idx < char_freqs_list_len-1
    } {
      fos.write(s"  '$char' -> $freq${if(last_comma)"," else ""}\n".getBytes)
    }
    fos.write(")".getBytes)
    fos.close()
  }

  def loadCodeTableFromFile(filename:String):CodeTable = {
    (for {
      line <- io.Source.fromFile(filename).getLines()
      char_and_bits = line.split(":")
      char = char_and_bits(0).trim().head
      bits = char_and_bits(1).trim().split(" ").map(_.toInt).toList
    } yield (char, bits)).toMap
  }

  def loadCodeTreeFromFile(filename:String):CodeTree = {
    createCodeTree((for {
      line <- io.Source.fromFile(filename).getLines()
      char_and_freq = line.split(":")
      if char_and_freq.length == 2
      char = char_and_freq(0).trim.head
      freq = char_and_freq(1).trim.toInt
    } yield (char, freq)).toMap)
  }

  private def bits2byte(bits:Seq[Bit]):Byte = {
    bits.zip(List(128, 64, 32, 16, 8, 4, 2, 1)).foldLeft(0) {
      case (res, (bit, x)) => res + bit*x
    }.toByte
  }

  def encodeMessage(message:String, code_table:CodeTable):Option[Array[Byte]] = {
    if(message.distinct.exists(c => !code_table.contains(c))) {
      println(message.distinct.filter(c => !code_table.contains(c)))
      None
    }
    else {
      val bits = message.flatMap(c => code_table(c))
      val len = {
        if(bits.length % 8 == 0) bits.length
        else 8*((bits.length/8f).toInt+1)
      }
      val ee = bits.padTo(len, 0)
      Some(ee.grouped(8).map(l => bits2byte(l)).toArray)
    }
  }

  private def bit(byte:Byte, bit_pos:Int):Int = if((byte & bit_pos) > 0) 1 else 0

  def bytes2BitList(d:Seq[Byte]):Seq[Bit] = {
    d.flatMap(x => {
      List(
        bit(x, 128),
        bit(x, 64),
        bit(x, 32),
        bit(x, 16),
        bit(x, 8),
        bit(x, 4),
        bit(x, 2),
        bit(x, 1)
      )
    })
  }

  def decodeMessage(tree: CodeTree, message:Seq[Byte], delimiter:Char):Option[String] = {
    decode(tree, bytes2BitList(message), delimiter).map(_.mkString)
  }
}