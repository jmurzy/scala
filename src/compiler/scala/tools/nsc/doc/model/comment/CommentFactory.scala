/* NSC -- new Scala compiler -- Copyright 2007-2010 LAMP/EPFL */

package scala.tools.nsc
package doc
package model
package comment

import reporters.Reporter
import util.Position

import scala.collection._
import scala.util.matching.Regex
import scala.annotation.switch

/** The comment parser transforms raw comment strings into `Comment` objects. Call `parse` to run the parser. Note that
  * the parser is stateless and should only be built once for a given Scaladoc run.
  *
  * @param reporter The reporter on which user messages (error, warnings) should be printed.
  *
  * @author Manohar Jonnalagedda
  * @author Gilles Dubochet */
final class CommentFactory(val reporter: Reporter) { parser =>

  val endOfText      = '\u0003'
  val endOfLine      = '\u000A'

  /** Something that should not have happened, happened, and Scaladoc should exit. */
  protected def oops(msg: String): Nothing =
    throw FatalError("program logic: " + msg)

  protected val CleanHtml =
  new Regex("""</?(p|h\d|pre|dl|dt|dd|ol|ul|li|blockquote|div|hr|br|br).*/?>""")

  protected val ShortLineEnd =
  new Regex("""\.|</?.*>""")

  /** The body of a comment, dropping start and end markers. */
  protected val CleanComment =
    new Regex("""(?s)\s*/\*\*((?:[^\*]\*)*)\*/\s*""")

  /** The body of a line, dropping the start star-marker, one leading whitespace and all trailing whitespace. */
  protected val CleanCommentLine =
    new Regex("""\*\s?(.*)""")

  /** A Scaladoc tag not linked to a symbol. Returns the name of the tag, and the rest of the line. */
  protected val SimpleTag =
    new Regex("""\s*@(\S+)\s+(.*)""")

  /** A Scaladoc tag linked to a symbol. Returns the name of the tag, the name of the symbol, and the rest of the
    * line. */
  protected val SymbolTag =
    new Regex("""\s*@(param|tparam|throws)\s+(\S*)\s*(.*)""")

  /** The start of a scaladoc code block */
  protected val CodeBlockStart =
    new Regex("""(.*)\{\{\{(.*)""")

  /** The end of a scaladoc code block */
  protected val CodeBlockEnd =
    new Regex("""(.*)\}\}\}(.*)""")

  /** A key used for a tag map. The key is built from the name of the tag and from the linked symbol if the tag has one.
    * Equality on tag keys is structural. */
  protected sealed abstract class TagKey {
    def name: String
  }

  protected final case class SimpleTagKey(name: String) extends TagKey
  protected final case class SymbolTagKey(name: String, symbol: String) extends TagKey

  /** Parses a raw comment string into a `Comment` object.
    * @param comment The raw comment string (including start and end markers) to be parsed.
    * @param pos     The position of the comment in source. */
  def parse(comment: String, pos: Position): Comment = {
    /** The cleaned raw comment as a list of lines. Cleaning removes comment start and end markers, line start markers
      * and unnecessary whitespace. */
    val cleaned: List[String] = {
      def cleanLine(line: String): Option[String] = {
        line.trim match {
          case CleanCommentLine(ctl) => Some(ctl)
          case "" =>
            None
          case tl =>
            reporter.warning(pos, "Comment has no start-of-line marker ('*')")
            Some(tl)
      }
    }
    comment.trim.stripPrefix("/*").stripSuffix("*/").lines.toList flatMap (cleanLine(_))
  }

  /** Parses a comment (in the form of a list of lines) to a Comment instance, recursively on lines. To do so, it
    * splits the whole comment into main body and tag bodies, then runs the `WikiParser` on each body before creating
    * the comment instance.
    *
    * @param body        The body of the comment parsed until now.
    * @param tags        All tags parsed until now.
    * @param lastTagKey  The last parsed tag, or `None` if the tag section hasn't started. Lines that are not tagged
    *                    are part of the previous tag or, if none exists, of the body.
    * @param remaining   The lines that must still recursively be parsed.
    * @param inCodeBlock Whether the next line is part of a code block (in which no tags must be read). */
  def parse0(docBody: String, tags: Map[TagKey, List[String]], lastTagKey: Option[TagKey], remaining: List[String], inCodeBlock: Boolean): Comment = {
    remaining match {

      case CodeBlockStart(before, after) :: ls if (!inCodeBlock) =>
        if (before.trim != "")
          parse0(docBody, tags, lastTagKey, before :: ("{{{" + after) :: ls, false)
        else if (after.trim != "")
          parse0(docBody, tags, lastTagKey, after :: ls, true)
        else
          parse0(docBody, tags, lastTagKey, ls, true)

      case CodeBlockEnd(before, after) :: ls =>
        if (before.trim != "")
          parse0(docBody, tags, lastTagKey, before :: ("}}}" + after) :: ls, true)
        else if (after.trim != "")
          parse0(docBody, tags, lastTagKey, after :: ls, false)
        else
          parse0(docBody, tags, lastTagKey, ls, false)

      case SymbolTag(name, sym, body) :: ls if (!inCodeBlock) =>
        val key = SymbolTagKey(name, sym)
        val value = body :: tags.getOrElse(key, Nil)
        parse0(docBody, tags + (key -> value), Some(key), ls, inCodeBlock)

      case SimpleTag(name, body) :: ls if (!inCodeBlock) =>
        val key = SimpleTagKey(name)
        val value = body :: tags.getOrElse(key, Nil)
        parse0(docBody, tags + (key -> value), Some(key), ls, inCodeBlock)

      case line :: ls if (lastTagKey.isDefined) =>
        val key = lastTagKey.get
        val value =
          ((tags get key): @unchecked) match {
            case Some(b :: bs) => (b + endOfLine + line) :: bs
            case None => oops("lastTagKey set when no tag exists for key")
          }
        parse0(docBody, tags + (key -> value), lastTagKey, ls, inCodeBlock)

      case line :: ls =>
        val newBody = if (docBody == "") line else docBody + endOfLine + line
        parse0(newBody, tags, lastTagKey, ls, inCodeBlock)

      case Nil =>

        val bodyTags: mutable.Map[TagKey, List[Body]] =
          mutable.Map((tags map { case (key, values) => key -> (values map (parseWiki(_, pos))) }).toSeq:_*)

        def oneTag(key: SimpleTagKey): Option[Body] =
          ((bodyTags remove key): @unchecked) match {
            case Some(r :: rs) =>
              if (!rs.isEmpty) reporter.warning(pos, "Only one '@" + key.name + "' tag is allowed")
              Some(r)
            case None => None
          }

        def allTags(key: SimpleTagKey): List[Body] =
          (bodyTags remove key) getOrElse Nil

        def allSymsOneTag(key: TagKey): Map[String, Body] = {
          val keys: Seq[SymbolTagKey] =
            bodyTags.keysIterator.toSeq flatMap {
              case stk: SymbolTagKey if (stk.name == key.name) => Some(stk)
              case stk: SimpleTagKey if (stk.name == key.name) =>
                reporter.warning(pos, "Tag '@" + stk.name + "' must be followed by a symbol name")
                None
              case _ => None
            }
          val pairs: Seq[(String, Body)] =
            for (key <- keys) yield {
              val bs = (bodyTags remove key).get
              if (bs.length > 1)
                reporter.warning(pos, "Only one '@" + key.name + "' tag for symbol " + key.symbol + " is allowed")
              (key.symbol, bs.head)
            }
          Map.empty[String, Body] ++ pairs
        }

        val com = new Comment {
          val body        = parseWiki(docBody, pos)
          val authors     = allTags(SimpleTagKey("author"))
          val see         = allTags(SimpleTagKey("see"))
          val result      = oneTag(SimpleTagKey("return"))
          val throws      = allSymsOneTag(SimpleTagKey("throws"))
          val valueParams = allSymsOneTag(SimpleTagKey("param"))
          val typeParams  = allSymsOneTag(SimpleTagKey("tparam"))
          val version     = oneTag(SimpleTagKey("version"))
          val since       = oneTag(SimpleTagKey("since"))
          val todo        = allTags(SimpleTagKey("todo"))
          val deprecated  = oneTag(SimpleTagKey("deprecated"))
          val note        = allTags(SimpleTagKey("note"))
          val example     = allTags(SimpleTagKey("example"))
          val short = {
            val shortText = ShortLineEnd.findFirstMatchIn(docBody) match {
              case None => docBody
              case Some(m) => docBody.take(m.start)
            }
            val safeText = CleanHtml.replaceAllIn(shortText, "") // get rid of all layout-busting html tags
            parseWiki(safeText, pos) match {
              case Body(Paragraph(inl) :: _) => inl
              case _ =>
                if (safeText != "")
                  reporter.warning(pos, "Comment must start with a sentence")
                Text("")
            }
          }
        }

        for ((key, _) <- bodyTags)
          reporter.warning(pos, "Tag '@" + key.name + "' is not recognised")

        com

      }
    }
    parse0("", Map.empty, None, cleaned, false)
  }

  /** Parses a string containing wiki syntax into a `Comment` object. Note that the string is assumed to be clean:
    *  * Removed Scaladoc start and end markers.
    *  * Removed start-of-line star and one whitespace afterwards (if present).
    *  * Removed all end-of-line whitespace.
    *  * Only `endOfLine` is used to mark line endings. */
  def parseWiki(string: String, pos: Position): Body =
    new WikiParser(string.toArray, pos).document()

  /** TODO
    *
    * @author Ingo Maier
    * @author Manohar Jonnalagedda
    * @author Gilles Dubochet */
  protected final class WikiParser(val buffer: Array[Char], pos: Position) extends CharReader(buffer) { wiki =>

    def document(): Body = {
      nextChar()
      val blocks = new mutable.ListBuffer[Block]
      while(char != endOfText)
        blocks += block()
      Body(blocks.toList)
    }

    /* BLOCKS */

    /** {{{ block ::= code | title | hrule | para }}} */
    def block(): Block = {
      if (check("{{{"))
        code()
      else if (check("="))
        title()
      else if (check("----"))
        hrule()
      else if (check(" - "))
        listBlock(countWhitespace, '-', UnorderedList)
      else if (check(" 1 "))
        listBlock(countWhitespace, '1', OrderedList)
      else {
        para()
      }
    }

    /**
     * {{{
     *   nListBlock ::= nLine { mListBlock }
     *   nLine ::= nSpc '*' para '\n'
     * }}}
     * Where n and m stand for the number of spaces. When m > n, a new list is nested.  */
	def listBlock(indentation: Int, marker: Char, constructor: (Seq[Block] => Block)): Block = {
      var count  = indentation
      val start  = " " * count + marker + " "
      var chk    = check(start)
      var line   = listLine(indentation, marker)
      val blocks = mutable.ListBuffer.empty[Block]
      while (chk) {
        blocks += line
        count = countWhitespace
        if (count > indentation) { // nesting-in
          blocks += listBlock(count, marker, constructor) // TODO is tailrec really needed here?
        }
        chk = check(start)
        if (chk) { line = listLine(indentation, marker) }
      }
      constructor(blocks)
    }

    def listLine(indentation: Int, marker: Char): Block = {
      jump(" " * indentation + marker + " ")
      val p = Paragraph(inline(check(Array(endOfLine))))
      blockEnded("end of list line ")
      p
    }

    /** {{{ code ::= "{{{" { char } '}' "}}" '\n' }}} */
    def code(): Block = {
      jump("{{{")
      readUntil("}}}")
      if (char == endOfText)
        reportError(pos, "unclosed code block")
      else
        jump("}}}")
      blockEnded("code block")
      Code(getRead)
    }

    /** {{{ title ::= ('=' inline '=' | "==" inline "==" | ...) '\n' }}} */
    def title(): Block = {
      val inLevel = repeatJump("=")
      val text = inline(check(Array.fill(inLevel)('=')))
      val outLevel = repeatJump("=", inLevel)
      if (inLevel != outLevel)
        reportError(pos, "unbalanced or unclosed heading")
      blockEnded("heading")
      Title(text, inLevel)
    }

    /** {{{ hrule ::= "----" { '-' } '\n' }}} */
    def hrule(): Block = {
      repeatJump("-")
      blockEnded("horizontal rule")
      HorizontalRule()
    }

    /** {{{ para ::= inline '\n' }}} */
    def para(): Block = {
      def checkParaEnd(): Boolean = {
        check(Array(endOfLine, endOfLine)) ||
        check(Array(endOfLine, '=')) ||
        check(Array(endOfLine, '{', '{', '{')) ||
        check(Array(endOfLine, ' ', '-', ' '))
      }
      val p = Paragraph(inline(checkParaEnd()))
      while (char == endOfLine && char != endOfText)
        nextChar()
      p
    }

    /* INLINES */

    def inline(isBlockEnd: => Boolean): Inline =
      inline(isBlockEnd, isBlockEnd)

    def inline(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      def inline0(): Inline = {
        if (check("'''"))
          bold(isInlineEnd, isBlockEnd)
        else if (check("''"))
          italic(isInlineEnd, isBlockEnd)
        else if (check("`"))
          monospace(isInlineEnd, isBlockEnd)
        else if (check("__"))
          underline(isInlineEnd, isBlockEnd)
        else if (check("^"))
          superscript(isInlineEnd, isBlockEnd)
        else if (check(",,"))
          subscript(isInlineEnd, isBlockEnd)
        else if (check("[["))
          link(isInlineEnd, isBlockEnd)
        else {
          readUntil { check("''") || char == '`' || check("__") || char == '^' || check(",,") || check("[[") || isInlineEnd || isBlockEnd || char == endOfLine }
          Text(getRead())
        }
      }
      val inlines: List[Inline] = {
        val iss = mutable.ListBuffer.empty[Inline]
        iss += inline0()
        while(!isInlineEnd && !isBlockEnd && !checkParaEnded) {
          if (char == endOfLine) nextChar()
          val current = inline0()
          (iss.last, current) match {
            case (Text(t1), Text(t2)) =>
              iss.update(iss.length - 1, Text(t1 + endOfLine + t2))
            case _ => iss += current
          }
        }
        iss.toList
      }
      inlines match {
        case Nil => Text("")
        case i :: Nil => i
        case i :: is => Chain(i :: is)
      }
    }

    def bold(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump("'''")
      val i = inline(check("'''"), isBlockEnd)
      jump("'''")
      Bold(i)
    }

    def italic(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump("''")
      val i = inline(check("''"), isBlockEnd)
      jump("''")
      Italic(i)
    }

    def monospace(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump("`")
      readUntil { char == '`' }
      jump("`")
      Monospace(getRead())
    }

    def underline(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump("__")
      val i = inline(check("__"), isBlockEnd)
      jump("__")
      Underline(i)
    }

    def superscript(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump("^")
      val i = inline(check("^"), isBlockEnd)
      jump("^")
      Superscript(i)
    }

    def subscript(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump(",,")
      val i = inline(check(",,"), isBlockEnd)
      jump(",,")
      Subscript(i)
    }

    def link(isInlineEnd: => Boolean, isBlockEnd: => Boolean): Inline = {
      jump("[[")
      readUntil { check("]]") }
      jump("]]")
      val read = getRead()
      val (target, title) = {
    	  val index = read.indexOf(' ');
    	  val split = read.splitAt( if (index > -1) index else 0 )
    	  if (split._1 == "")
          (split._2, None)
    	  else
          (split._1, Some(split._2.trim))
      }
      Link(target, title)
    }

    /* UTILITY */

    /** {{{ eol ::= { whitespace } '\n' }}} */
    def blockEnded(blockType: String): Unit = {
      if (char != endOfLine && char != endOfText) {
        reportError(pos, "no additional content on same line after " + blockType)
        jumpUntil(endOfLine)
      }
      while (char == endOfLine)
        nextChar()
    }

    def checkParaEnded(): Boolean = {
      char == endOfText || check(Array(endOfLine, endOfLine)) || check(Array(endOfLine, '{', '{', '{')) || check(Array(endOfLine, '\u003D'))
    }

    def reportError(pos: Position, message: String): Unit =
      reporter.warning(pos, message)

  }

  protected sealed class CharReader(buffer: Array[Char]) { reader =>

    var char: Char = _
    var offset: Int = 0

    final def nextChar(): Unit = {
      if (offset >= buffer.length)
        char = endOfText
      else {
        char = buffer(offset)
        offset += 1
      }
    }

    implicit def strintToChars(s: String): Array[Char] = s.toArray

    def store(body: => Unit): String = {
      val pre = offset
      body
      val post = offset
      buffer.toArray.slice(pre, post).toString
    }

    final def check(chars: Array[Char]): Boolean = {
      val poff = offset
      val pc = char
      val ok = jump(chars)
      offset = poff
      char = pc
      ok
    }

    def checkSkipWhitespace(chars: Array[Char]): Boolean = {
      assert(chars.head!=' ') // or it makes no sense
      val poff = offset
      val pc = char
      jumpWhitespace
      val ok = jump(chars)
      offset = poff
      char = pc
      ok
    }

    def countWhitespace:Int = {
      var count = 0
      val poff = offset
      val pc = char
      while (isWhitespace(char) && char!=endOfText) {
        nextChar()
        count += 1
      }
      offset = poff
      char = pc
      count
    }

    /* JUMPERS */

    final def jump(chars: Array[Char]): Boolean = {
      var index = 0
      while (index < chars.length && char == chars(index) && char != endOfText) {
        nextChar()
        index += 1
      }
      index == chars.length
    }

    final def checkedJump(chars: Array[Char]): Boolean = {
      val poff = offset
      val pc = char
      val ok = jump(chars)
      if (!ok) {
        offset = poff
        char = pc
      }
      ok
    }

    final def repeatJump(chars: Array[Char], max: Int): Int = {
      var count = 0
      var more = true
      while (more && count < max) {
        if (!checkedJump(chars))
          more = false
        else
          count += 1
      }
      count
    }

    final def repeatJump(chars: Array[Char]): Int = {
      var count = 0
      var more = true
      while (more) {
        if (!checkedJump(chars))
          more = false
        else
          count += 1
      }
      count
    }

    final def jumpUntil(ch: Char): Int = {
      var count = 0
      while(char != ch && char != endOfText) {
        nextChar()
        count=count+1
      }
      count
    }

    final def jumpUntil(chars: Array[Char]): Int = {
      assert(chars.length > 0)
      var count = 0
      val c = chars(0)
      while(!check(chars) && char != endOfText) {
        nextChar()
        while (char != c && char != endOfText) {
          nextChar()
          count += 1
        }
      }
      count
    }

    final def jumpUntil(pred: => Boolean): Int = {
      var count = 0
      while (!pred && char != endOfText) {
        nextChar()
        count += 1
      }
      count
    }

    def jumpWhitespace() = jumpUntil(!isWhitespace(char))

    /* READERS */

    private val readBuilder = new mutable.StringBuilder

    final def getRead(): String = {
      val bld = readBuilder.toString
      readBuilder.clear()
      if (bld.length < 6) bld.intern else bld
    }

    final def readUntil(ch: Char): Int = {
      var count = 0
      while(char != ch && char != endOfText) {
        readBuilder += char
        nextChar()
      }
      count
    }

    final def readUntil(chars: Array[Char]): Int = {
      assert(chars.length > 0)
      var count = 0
      val c = chars(0)
      while(!check(chars) && char != endOfText) {
        readBuilder += char
        nextChar()
        while (char != c && char != endOfText) {
          readBuilder += char
          nextChar()
        }
      }
      count
    }

    final def readUntil(pred: => Boolean): Int = {
      var count = 0
      while (!pred && char != endOfText) {
        readBuilder += char
        nextChar()
      }
      count
    }

    /* CHARS CLASSES */

    def isWhitespace(c: Char) = (c: @switch) match {
      case ' ' | '\t' => true
      case _ => false
    }

  }

}
