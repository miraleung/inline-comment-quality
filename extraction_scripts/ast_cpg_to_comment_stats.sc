/** Extracts comments and stats given a CPG name.
  *  Usage example:
  *
  * ./joern --script /path/to/cpg_to_comment_stats.sc \
  *   --params name=hadoop-common-project,out=/tmp/jsons/output.json,importDir=/tmp/proc
  */

import io.shiftleft.codepropertygraph.generated.nodes.{
  AstNode,
  Block,
  Call,
  Comment,
  ControlStructure,
  Expression,
  FieldIdentifier,
  Identifier,
  JumpLabel,
  JumpTarget,
  Literal,
  Local,
  Method,
  MethodRef,
  Modifier,
  Return,
  TypeRef
}

import java.io.File
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import net.liftweb.json._
import scala.io.AnsiColor._
import scala.sys.process._
import scala.language.postfixOps // For ending ! in shell commands

object NodeType extends Enumeration {
  type NodeType = Value
  // TODO: Consider whether to prefix ControlStructure-typed nodes.
  // These include: If, else, try, throw, switch, continue, do, while, for, goto.
  val UNKNOWN          = Value("UNKNOWN")
  val AST              = Value("AST")
  val ASSIGNMENT       = Value("ASSIGNMENT")
  val BLOCK            = Value("BLOCK")
  val BREAK            = Value("BREAK")
  val CALL             = Value("CALL")
  val CAST             = Value("CAST")
  val COMMENT          = Value("COMMENT")
  val CONTROLSTRUCTURE = Value("CONTROLSTRUCTURE")
  val CONTINUE         = Value("CONTINUE")
  val DO               = Value("DO")
  val ELSE             = Value("ELSE")
  val EXPRESSION       = Value("EXPRESSION")
  val FIELDACCESS      = Value("FIELDACCESS")
  val FIELDIDENTIFIER  = Value("FIELDIDENTIFIER")
  val FOR              = Value("FOR")
  val GOTO             = Value("GOTO")
  val IDENTIFIER       = Value("IDENTIFIER")
  val IF               = Value("IF")
  val JUMPLABEL        = Value("JUMPLABEL")
  val JUMPTARGET       = Value("JUMPTARGET")
  val LITERAL          = Value("LITERAL")
  val LOCAL            = Value("LOCAL")
  val METHOD           = Value("METHOD")
  val METHODCTOR       = Value("METHODCTOR")
  val METHODREF        = Value("METHODREF")
  val MODIFIER         = Value("MODIFIER")
  val NEWOBJECT        = Value("NEWOBJECT")
  val OPERATOR         = Value("OPERATOR")
  val RETURN           = Value("RETURN")
  val SWITCH           = Value("SWITCH")
  val SYNCHRONIZED     = Value("SYNCHRONIZED")
  val THROW            = Value("THROW")
  val TRY              = Value("TRY")
  val TYPEREF          = Value("TYPEREF")
  val WHILE            = Value("WHILE")
}

object Scope extends Enumeration {
  type Scope = Value
  val ABSTRACT  = Value("ABSTRACT")
  val DEFAULT   = Value("DEFAULT")
  val PUBLIC    = Value("PUBLIC")
  val PROTECTED = Value("PROTECTED")
  val PRIVATE   = Value("PRIVATE")
}

// nodeType is a String instead of a NodeType for better JSON serialization purposes.
// Using NodeType, it serializes to something like {i: 2, name: "BLOCK"} instead of "BLOCK".
case class NodeAttribute(id: Long, nodeType: String, code: String, line: Option[Integer])

case class MethodAttribute(
  attribute: NodeAttribute,
  scope: Scope.Value,
  isStatic: Boolean,
  isSynchronized: Boolean,
  exception: Option[String],
  name: String,
  signature: String
)

case class CommentAttribute(
  attribute: NodeAttribute,
  filename: String,
  method: MethodAttribute,
  parent: NodeAttribute,
  // JSON serialization accepts only String keys.
  siblings: Map[String, NodeAttribute]
)

def prettyPrint(str: String) = {
  println(s"${MAGENTA}${BOLD}Comments> ${RESET}${GREEN}$str ${RESET}")
}

def errorPrint(str: String) = {
  println(s"${MAGENTA}${BOLD}Comments> ${RESET}${RED}Error: $str ${RESET}")
}

/** Returns the JSON representation of a comment attribute.
  */
def commentAttribToJson(commentAttrib: CommentAttribute): String = {
  implicit val formats = DefaultFormats
  net.liftweb.json.Serialization.write(commentAttrib)
}

def formatJsonFile(actualFilepath: String) = {
  val tempOutputPath = s"$actualFilepath.temp"
  s"cat $actualFilepath" #| s"python -m json.tool" #> new File(
    s"$tempOutputPath"
  ) #&& s"cp $tempOutputPath $actualFilepath" !
  val tempFile = new File(tempOutputPath)
  tempFile.delete()
}

/** Returns a file path with everything before projectName removed (inclusive). Example: filename
  * /path/to/project/subdir/file, projectName project Returns subdir/file
  */
def simplifyFilename(origFilename: String, projectName: String): String = {
  // Add 1 for the slash.
  origFilename.substring(origFilename.indexOf(projectName) + projectName.length + 1)
}

def nodeToAttrib(astNode: AstNode, projectName: String, debug: Boolean): NodeAttribute = {
  var nodeType = astNode match {
    // TODO: Consider whether to parse block types here. We'd need to descend into
    // a block's children for the actual code, and handle different kinds of nodes
    // as the first child. For instance, modifiers like "synchronized" need to be
    // handled, or if there is no modifier but some statement instead.
    // Sample query for digging:
    //   commentNode.astParent.astChildren.l(2).astChildren.l(2).astChildren.l
    case x: Block =>
      x.astChildren.l(0) match {
        case y if y.isInstanceOf[Modifier] && y.asInstanceOf[Modifier].modifierType.equals("SYNCHRONIZED") =>
          NodeType.SYNCHRONIZED
        case y =>
          if (debug) {
            println(s"Block node: ${y.code}")
          }
          NodeType.BLOCK
      }
    case x: Call =>
      val callName = x.asInstanceOf[Call].name
      if (callName.equals("<operator>.throw")) {
        NodeType.THROW
      } else if (callName.startsWith("<operator>.assignment") || callName.startsWith("<operators>.assignment")) {
        NodeType.ASSIGNMENT // Includes assignment{Division,Plus,Minus,Multiplication}
      } else if (callName.equals("<operator>.fieldAccess")) {
        NodeType.FIELDACCESS
      } else if (
        callName.equals("<operator>.alloc") || callName
          .equals("<operator>.arrayInitializer") || callName.equals("<init>")
      ) {
        NodeType.NEWOBJECT // new Foobar() or String dsArgs = {'a', 'b', 'c'}
      } else if (callName.equals("<operator>.cast")) {
        NodeType.CAST
      } else if (callName.startsWith("<operator>")) {
        NodeType.OPERATOR // Includes logical ops, conditional (aka ternary), math, {in/de}crement.
      } else {
        NodeType.CALL // Plain old method calls.
      }
    case _: Comment => NodeType.COMMENT
    case x: ControlStructure =>
      x.controlStructureType match {
        case "BREAK"    => NodeType.BREAK
        case "CONTINUE" => NodeType.CONTINUE
        case "DO"       => NodeType.DO
        case "WHILE"    => NodeType.WHILE
        case "FOR"      => NodeType.FOR
        case "GOTO"     => NodeType.GOTO
        case "IF"       => NodeType.IF
        case "ELSE"     => NodeType.ELSE
        case "TRY"      => NodeType.TRY
        case "SWITCH"   => NodeType.SWITCH
      }
    case _: FieldIdentifier => NodeType.FIELDIDENTIFIER
    case _: JumpLabel       => NodeType.JUMPLABEL
    case _: JumpTarget      => NodeType.JUMPTARGET
    case _: Literal         => NodeType.LITERAL
    case _: Local           => NodeType.LOCAL
    case _: Method          => NodeType.METHOD
    case _: MethodRef       => NodeType.METHODREF
    case _: Return          => NodeType.RETURN
    case _: TypeRef         => NodeType.TYPEREF
    case _: Identifier      => NodeType.IDENTIFIER
    case _: Expression      => NodeType.EXPRESSION
    case x =>
      if (debug) {
        println(s"UNKNOWN is of type ${x.getClass.getName}, code ${x.code}, node ${x.toString}")
      }
      NodeType.UNKNOWN
  }

  NodeAttribute(astNode.id, nodeType.toString, astNode.code, astNode.lineNumber)
}

def methodNodeToAttrib(methodNode: Method): MethodAttribute = {
  var methodScope = methodNode.code match {
    case x if x.contains("public ")    => Scope.PUBLIC
    case x if x.contains("protected ") => Scope.PROTECTED
    case x if x.contains("private ")   => Scope.PRIVATE
    case x if x.contains("default ")   => Scope.DEFAULT
    case x if x.contains("abstract ")  => Scope.ABSTRACT
    case _                             => Scope.PROTECTED
  }
  var isStatic       = methodNode.code.contains("static ")
  var isSynchronized = methodNode.code.contains("synchronized ")
  var exception = methodNode.code match {
    case x if x.contains("throws ") => Some(x.substring(x.indexOf("throws ") + "throws ".length))
    case _                          => None
  }
  var methodNodeType =
    if (methodNode.name.equals("<init>")) {
      NodeType.METHODCTOR
    } else {
      NodeType.METHOD
    }

  var methodNodeAttrib =
    NodeAttribute(methodNode.id, NodeType.METHOD.toString, methodNode.code, methodNode.lineNumber)
  MethodAttribute(
    methodNodeAttrib,
    methodScope,
    isStatic,
    isSynchronized,
    exception,
    methodNode.name,
    methodNode.signature
  )
}

def commentAstToAttrib(commentNode: Comment, projectName: String): CommentAttribute = {
  var filename   = simplifyFilename(commentNode.filename, projectName)
  var methodNode = commentNode.astParent
  // Assumes we'll eventually hit a method.
  while (!methodNode.isInstanceOf[Method]) {
    try {
      methodNode = methodNode.astParent
    } catch {
      case e: Exception =>
        println(
          s"Error occurred when looking up method for comment \"${commentNode.code}\""
            + s" at line ${commentNode.lineNumber} of file $filename: \n${e.getMessage}"
        )
        println(e.getStackTrace)
        sys.exit(1)
    }
  }

  var methodAttrib  = methodNodeToAttrib(methodNode.asInstanceOf[Method])
  var astParentNode = commentNode.astParent
  while (astParentNode.isInstanceOf[Block]) {
    try {
      astParentNode = astParentNode.astParent
    } catch {
      case e: Exception =>
        println(
          s"Error occurred when looking up non-block parent for \"${commentNode.code}\" "
            + s" at line  ${commentNode.lineNumber} of file $filename: \n${e.getMessage}"
        )

    }
  }

  var numYoungerSiblings  = 5
  var astParentNodeAttrib = nodeToAttrib(astParentNode, projectName, false)
  var commentNodeAttrib =
    NodeAttribute(commentNode.id, NodeType.COMMENT.toString, commentNode.code, commentNode.lineNumber)
  CommentAttribute(
    commentNodeAttrib,
    filename,
    methodAttrib,
    astParentNodeAttrib,
    commentNode.astParent.astChildren
      .filter(_.order > commentNode.order)
      .l
      .slice(0, numYoungerSiblings)
      .map(x => nodeToAttrib(x, projectName, true))
      .zipWithIndex
      .map(_.swap)
      .toMap
      .map { case (k, v) => k.toString -> v }
  )
}

/** Command-line params.
  *
  * @param name
  *   name of the CPG under workspace/
  * @param out
  *   filename of output JSON.
  */
@main def exec(name: String, out: String, importDir: String = "") = {
  // Check that the output directory exists.
  val outputPath = Paths.get(out)
  if (!outputPath.getParent.toFile.exists) {
    errorPrint(s"Output file directory not found")
    sys.exit(1)
  }

  // Load CPG.
  val loadCpgResult = open(name)
  if (loadCpgResult.isEmpty) {
    errorPrint(s"No existing CPG found for $name")
    if (importDir.isEmpty) {
      sys.exit(1)
    } else {
      importCode(s"$importDir/$name")
      prettyPrint("Finished importing, processing starting...")
    }
  }

  // Extract comments.
  var commentAttribs   = cpg.method.ast.isComment.l.map(x => commentAstToAttrib(x, name))
  implicit val formats = DefaultFormats
  val jsonString       = net.liftweb.json.Serialization.write(commentAttribs)
  Files.write(outputPath, jsonString.getBytes(StandardCharsets.UTF_8))
  formatJsonFile(outputPath.toString)
  prettyPrint(s"JSON written to $out")
}

// TODO
// - Revisit: Parse blocks by types?
// - Revisit: Rename enums?
// - Explore PDG, CFG stats
