package com.github.dunnololda.simplenet

import collection.{GenTraversableOnce, immutable, mutable}
import collection.mutable.ArrayBuffer

/**
 * Represents JSON Object
 */
// TODO: add example usages and tell about alphabetical order for keys in patterns!
class State(args:Any*) extends Map[String, Any] {
  //private val log = MySimpleLogger(this.getClass.getName)
  
  //add(args:_*)
  private val inner_map:Map[String, Any] = {
    val pewpew = args.flatMap(_ match {
      case elem:(String, Any) => List(elem)
      case elem:State => elem.toList
      case elem:Any => List((elem.toString -> true))
    })
    Map(pewpew:_*)
  }

  def neededKeys(foreach_func:PartialFunction[(String, Any), Any]) {  // maybe rename this func
    foreach(elem => if(foreach_func.isDefinedAt(elem)) foreach_func(elem))
  }
  
  /*private def add(args:Any*):this.type = {
    args.foreach(arg => {
      arg match {
        case elem:(String, Any) => this += (elem)
        case elem:State => this ++= elem
        case elem:Any => this += ((elem.toString -> true))
      }
    })
    this
  }  */
  /*def addJson(json:String) {this ++= State.fromJsonStringOrDefault(json)}*/

  override def toString() = mkString("State(", ", ", ")")

  private def list2JsonArrayString(l:List[Any]):String = {  // maybe make it public and move to State object. I'll do it on real purpose appeared
    val sb = new StringBuilder("[")
    for {
      (value, index) <- l.zipWithIndex
      opt_comma = if(index != l.size-1) ", " else ""
    } {
      value match {
        case s:State => sb.append(s.toJsonString+opt_comma)
        case l:List[Any] => sb.append(list2JsonArrayString(l)+opt_comma)
        case str:String => sb.append("\""+str+"\""+opt_comma)
        case any_other_val => sb.append(any_other_val.toString+opt_comma)
      }
    }
    sb.append("]")
    sb.toString()
  }
  def toJsonString:String = {
    val sb = new StringBuilder("{")
    val last_key_pos = keys.size-1
    var next_elem_pos = 0
    for {
      key <- keys
      value = apply(key)
    } {
      val opt_comma = if(next_elem_pos != last_key_pos) ", " else ""
      value match {
        case s:State => sb.append("\""+key+"\":"+s.toJsonString+opt_comma)
        case l:List[Any] => sb.append("\""+key+"\":"+list2JsonArrayString(l)+opt_comma)
        case str:String => sb.append("\""+key+"\":\""+str+"\""+opt_comma)
        case any_other_val => sb.append("\""+key+"\":"+any_other_val.toString+opt_comma)
      }
      next_elem_pos += 1
    }
    sb.append("}")
    sb.toString()
  }
  
  def value[A : Manifest](key:String):Option[A] = this.get(key) match {
    case Some(value) =>
      manifest[A] match {
        case Manifest.Long    => if(value.isInstanceOf[Number]) Some(value.asInstanceOf[Number].longValue().asInstanceOf[A])   else None
        case Manifest.Float   => if(value.isInstanceOf[Number]) Some(value.asInstanceOf[Number].floatValue().asInstanceOf[A])  else None
        case Manifest.Double  => if(value.isInstanceOf[Number]) Some(value.asInstanceOf[Number].doubleValue().asInstanceOf[A]) else None
        case Manifest.Int     => if(value.isInstanceOf[Number]) Some(value.asInstanceOf[Number].intValue().asInstanceOf[A])    else None
        case Manifest.Short   => if(value.isInstanceOf[Number]) Some(value.asInstanceOf[Number].shortValue().asInstanceOf[A])  else None
        case Manifest.Byte    => if(value.isInstanceOf[Number]) Some(value.asInstanceOf[Number].byteValue().asInstanceOf[A])   else None
        case Manifest.Char    => if(value.isInstanceOf[Char])   Some(value.asInstanceOf[Char].asInstanceOf[A])                 else None
        case Manifest.Boolean =>
          value.toString match {
            case "true"  => Some(true.asInstanceOf[A])
            case "yes"   => Some(true.asInstanceOf[A])
            case "on"    => Some(true.asInstanceOf[A])
            case "1"     => Some(true.asInstanceOf[A])
            case "false" => Some(false.asInstanceOf[A])
            case "no"    => Some(false.asInstanceOf[A])
            case "off"   => Some(false.asInstanceOf[A])
            case "0"     => Some(false.asInstanceOf[A])
            case _       => None
          }
        case m => if(m.runtimeClass.isInstance(value)) Some(value.asInstanceOf[A]) else None
      }
    case None => None
  }
  
  def valueOrDefault[A : Manifest](key:String, default:A):A = {
    get(key) match {
      case Some(value) =>
        manifest[A] match {
          case Manifest.Long    => if(value.isInstanceOf[Number]) value.asInstanceOf[Number].longValue().asInstanceOf[A]   else default
          case Manifest.Float   => if(value.isInstanceOf[Number]) value.asInstanceOf[Number].floatValue().asInstanceOf[A]  else default
          case Manifest.Double  => if(value.isInstanceOf[Number]) value.asInstanceOf[Number].doubleValue().asInstanceOf[A] else default
          case Manifest.Int     => if(value.isInstanceOf[Number]) value.asInstanceOf[Number].intValue().asInstanceOf[A]    else default
          case Manifest.Short   => if(value.isInstanceOf[Number]) value.asInstanceOf[Number].shortValue().asInstanceOf[A]  else default
          case Manifest.Byte    => if(value.isInstanceOf[Number]) value.asInstanceOf[Number].byteValue().asInstanceOf[A]   else default
          case Manifest.Char    => if(value.isInstanceOf[Char])   value.asInstanceOf[Char].asInstanceOf[A]                 else default
          case Manifest.Boolean =>
            value.toString match {
              case "true"  => true.asInstanceOf[A]
              case "yes"   => true.asInstanceOf[A]
              case "on"    => true.asInstanceOf[A]
              case "1"     => true.asInstanceOf[A]
              case "false" => false.asInstanceOf[A]
              case "no"    => false.asInstanceOf[A]
              case "off"   => false.asInstanceOf[A]
              case "0"     => false.asInstanceOf[A]
              case _       => default
            }
          case m => if(m.runtimeClass.isInstance(value)) value.asInstanceOf[A] else default
        }
      case None => default
    }
  }

  def get(key: String): Option[Any] = inner_map.get(key)

  def iterator: Iterator[(String, Any)] = inner_map.iterator

  def -(key: String): State = State(iterator.toSeq.filterNot(_._1 == key):_*)

  def +[B1 >: Any](kv: (String, B1)): State = State((kv :: iterator.toList):_*)

  override def ++[B1 >: Any](xs: GenTraversableOnce[(String, B1)]): State = State((iterator.toList ::: xs.toList):_*)
}

class StateBuilder {
  private val inner_buffer = ArrayBuffer[(String, Any)]()
  def +=(elem:(String, Any)*):this.type = {inner_buffer ++= elem; this}
  def ++=(elems:Seq[(String, Any)]):this.type = {inner_buffer ++= elems; this}
  def toState = State(inner_buffer:_*)
  def clear() {inner_buffer.clear()}
  def nonEmpty:Boolean = {inner_buffer.nonEmpty}
}

object State {
  def apply(args:Any*) = new State(args:_*)
  def unapplySeq(data:Any) = {
    data match {
      case state:State => Some(state.toList.sortWith((e1, e2) => e1._1 < e2._1))
      case _ => None
    }
  }

  private lazy val json_parser = new JSONParser
  def fromJsonString(json:String):Option[State] = json_parser.evaluate(json)
  def fromJsonStringOrDefault(json:String, default_state:State = State()):State = json_parser.evaluate(json) match {
    case Some(s:State) => s
    case None => default_state
  }

  def newBuilder = new StateBuilder
}