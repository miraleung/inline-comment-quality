package io.joern.javasrc2cpg.passes

import com.github.javaparser.ast.`type`.TypeParameter
import com.github.javaparser.ast.{CompilationUnit, Node, NodeList, PackageDeclaration}
import com.github.javaparser.ast.body.{
  AnnotationDeclaration,
  BodyDeclaration,
  CallableDeclaration,
  ClassOrInterfaceDeclaration,
  ConstructorDeclaration,
  EnumConstantDeclaration,
  FieldDeclaration,
  InitializerDeclaration,
  MethodDeclaration,
  Parameter,
  TypeDeclaration,
  VariableDeclarator
}
import com.github.javaparser.ast.comments.{Comment, LineComment}
import com.github.javaparser.ast.expr.AssignExpr.Operator
import com.github.javaparser.ast.expr.{
  AnnotationExpr,
  ArrayAccessExpr,
  ArrayCreationExpr,
  ArrayInitializerExpr,
  AssignExpr,
  BinaryExpr,
  BooleanLiteralExpr,
  CastExpr,
  CharLiteralExpr,
  ClassExpr,
  ConditionalExpr,
  DoubleLiteralExpr,
  EnclosedExpr,
  Expression,
  FieldAccessExpr,
  InstanceOfExpr,
  IntegerLiteralExpr,
  LambdaExpr,
  LiteralExpr,
  LongLiteralExpr,
  MarkerAnnotationExpr,
  MethodCallExpr,
  NameExpr,
  NormalAnnotationExpr,
  NullLiteralExpr,
  ObjectCreationExpr,
  SingleMemberAnnotationExpr,
  StringLiteralExpr,
  SuperExpr,
  TextBlockLiteralExpr,
  ThisExpr,
  UnaryExpr,
  VariableDeclarationExpr
}
import com.github.javaparser.ast.nodeTypes.{NodeWithName, NodeWithSimpleName}
import com.github.javaparser.ast.stmt.{
  AssertStmt,
  BlockStmt,
  BreakStmt,
  CatchClause,
  ContinueStmt,
  DoStmt,
  EmptyStmt,
  ExplicitConstructorInvocationStmt,
  ExpressionStmt,
  ForEachStmt,
  ForStmt,
  IfStmt,
  LabeledStmt,
  ReturnStmt,
  Statement,
  SwitchEntry,
  SwitchStmt,
  SynchronizedStmt,
  ThrowStmt,
  TryStmt,
  WhileStmt
}
import com.github.javaparser.resolution.{SymbolResolver, UnsolvedSymbolException}
import com.github.javaparser.resolution.declarations.{
  ResolvedConstructorDeclaration,
  ResolvedFieldDeclaration,
  ResolvedMethodDeclaration,
  ResolvedMethodLikeDeclaration,
  ResolvedReferenceTypeDeclaration
}
import com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap
import com.github.javaparser.resolution.types.{ResolvedReferenceType, ResolvedType, ResolvedTypeVariable}
import io.joern.javasrc2cpg.util.BindingTable.createBindingTable
import io.joern.javasrc2cpg.util.NodeBuilders.{assignmentNode, indexAccessNode}
import io.joern.javasrc2cpg.util.Scope.ScopeTypes.{BlockScope, MethodScope, NamespaceScope, TypeDeclScope}
import io.joern.javasrc2cpg.util.Scope.{NamedVariableNodeType, WildcardImportName}
import io.joern.javasrc2cpg.util.{
  BindingTable,
  BindingTableAdapterForJavaparser,
  BindingTableAdapterForLambdas,
  BindingTableEntry,
  LambdaBindingInfo,
  NodeTypeInfo,
  Scope,
  TypeInfoCalculator
}
import io.joern.javasrc2cpg.util.TypeInfoCalculator.TypeConstants
import io.joern.javasrc2cpg.util.Util.{
  composeMethodFullName,
  composeMethodLikeSignature,
  fieldIdentifierNode,
  operatorCallNode,
  rootCode,
  rootType
}
import io.shiftleft.codepropertygraph.generated.{
  ControlStructureTypes,
  DispatchTypes,
  EdgeTypes,
  EvaluationStrategies,
  ModifierTypes,
  NodeTypes,
  Operators
}
import io.shiftleft.codepropertygraph.generated.nodes.{
  ExpressionNew,
  HasFullName,
  HasSignature,
  NewAnnotation,
  NewAnnotationLiteral,
  NewAnnotationParameter,
  NewAnnotationParameterAssign,
  NewArrayInitializer,
  NewBinding,
  NewBlock,
  NewCall,
  NewClosureBinding,
  NewComment,
  NewControlStructure,
  NewFieldIdentifier,
  NewIdentifier,
  NewJumpTarget,
  NewLiteral,
  NewLocal,
  NewMember,
  NewMethod,
  NewMethodParameterIn,
  NewMethodRef,
  NewMethodReturn,
  NewModifier,
  NewNamespaceBlock,
  NewNode,
  NewReturn,
  NewTypeDecl,
  NewTypeRef,
  NewUnknown
}
import io.joern.x2cpg.{Ast, AstCreatorBase}
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.passes.frontend.TypeNodePass
import io.shiftleft.codepropertygraph.generated.nodes.AstNode.PropertyDefaults
import io.shiftleft.passes.IntervalKeyPool
import org.slf4j.LoggerFactory
import overflowdb.BatchedUpdate.DiffGraphBuilder

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOptional
import scala.language.{existentials, implicitConversions}
import scala.util.{Failure, Success, Try}

case class ClosureBindingEntry(node: NamedVariableNodeType, binding: NewClosureBinding)

case class LambdaImplementedInfo(
  implementedInterface: Option[ResolvedReferenceType],
  implementedMethod: Option[ResolvedMethodDeclaration]
)

case class PartialConstructor(initNode: NewCall, initArgs: Seq[Ast], blockAst: Ast)

case class ExpectedType(fullName: String, resolvedType: Option[ResolvedType] = None)
object ExpectedType {
  val default: ExpectedType = ExpectedType(TypeConstants.UnresolvedType)
  val Int: ExpectedType     = ExpectedType(TypeConstants.Int)
  val Boolean: ExpectedType = ExpectedType(TypeConstants.Boolean)
  val Void: ExpectedType    = ExpectedType(TypeConstants.Void)
}

case class AstWithStaticInit(ast: Seq[Ast], staticInits: Seq[Ast])

object AstWithStaticInit {
  val empty: AstWithStaticInit = AstWithStaticInit(Seq.empty, Seq.empty)

  def apply(ast: Ast): AstWithStaticInit = {
    AstWithStaticInit(Seq(ast), staticInits = Seq.empty)
  }
}

/** Translate a Java Parser AST into a CPG AST
  */
class AstCreator(filename: String, javaParserAst: CompilationUnit, global: Global, symbolResolver: SymbolResolver)
    extends AstCreatorBase(filename) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  import AstCreator._

  private val scopeStack                       = Scope()
  private val typeInfoCalc: TypeInfoCalculator = TypeInfoCalculator(global, symbolResolver)
  private val partialConstructorQueue: mutable.ArrayBuffer[PartialConstructor] = mutable.ArrayBuffer.empty
  private val bindingTableCache = mutable.HashMap.empty[String, BindingTable]

  // TODO: Perhaps move this to a NameProvider or some such? Look at kt2cpg to see if some unified representation
  // makes sense.
  private val LambdaNamePrefix   = "lambda$"
  private val lambdaKeyPool      = new IntervalKeyPool(first = 0, last = Long.MaxValue)
  private val IndexNamePrefix    = "$idx"
  private val indexKeyPool       = new IntervalKeyPool(first = 0, last = Long.MaxValue)
  private val IterableNamePrefix = "$iterLocal"
  private val iterableKeyPool    = new IntervalKeyPool(first = 0, last = Long.MaxValue)

  /** Entry point of AST creation. Translates a compilation unit created by JavaParser into a DiffGraph containing the
    * corresponding CPG AST.
    */
  def createAst(): DiffGraphBuilder = {
    val ast = astForTranslationUnit(javaParserAst)
    storeInDiffGraph(ast)
    diffGraph
  }

  /** Copy nodes/edges of given `AST` into the diff graph
    */
  def storeInDiffGraph(ast: Ast): Unit = {
    Ast.storeInDiffGraph(ast, diffGraph)
  }

  private def addImportsToScope(compilationUnit: CompilationUnit): Unit = {
    val (asteriskImports, specificImports) = compilationUnit.getImports.asScala.toList.partition(_.isAsterisk)
    specificImports.foreach { importStmt =>
      val name = importStmt.getName.getIdentifier
      val importNode =
        NewIdentifier()
          .name(name)
          .typeFullName(importStmt.getNameAsString) // fully qualified name
      scopeStack.addToScope(name, NodeTypeInfo(importNode))
    }

    asteriskImports match {
      case imp :: Nil =>
        val importNode = NewIdentifier().name(WildcardImportName).typeFullName(imp.getNameAsString)
        scopeStack.addToScope(WildcardImportName, importNode)

      case _ => // Only try to guess a wildcard import if exactly one is defined
    }
  }

  /** Translate compilation unit into AST
    */
  private def astForTranslationUnit(compilationUnit: CompilationUnit): Ast = {
    try {
      val ast = astForPackageDeclaration(compilationUnit.getPackageDeclaration.toScala)

      scopeStack.pushNewScope(NamespaceScope)

      val namespaceBlockFullName = {
        ast.root.collect { case x: NewNamespaceBlock => x.fullName }.getOrElse("none")
      }

      addImportsToScope(compilationUnit)

      val typeDeclAsts = compilationUnit.getTypes.asScala.map { typ =>
        astForTypeDecl(typ, astParentType = NodeTypes.NAMESPACE_BLOCK, astParentFullName = namespaceBlockFullName)
      }

      val lambdaTypeDeclAsts = scopeStack.getLambdaDeclsInScope.toSeq

      scopeStack.popScope()
      ast.withChildren(typeDeclAsts).withChildren(lambdaTypeDeclAsts)
    } catch {
      case t: UnsolvedSymbolException =>
        logger.error(s"Unsolved symbol exception caught in $filename")
        throw t
      case t: Throwable =>
        logger.error(s"Parsing file $filename failed with $t")
        throw t
    }
  }

  /** Parses comments.
    */
  private def astForComment(c: Comment): Ast = {
    val commentNode = NewComment()
      .filename(absolutePath(filename))
      .lineNumber(line(c))
      .columnNumber(column(c))
      .code(c.getContent())
    Ast(commentNode)
  }

  /** Translate package declaration into AST consisting of a corresponding namespace block.
    */
  private def astForPackageDeclaration(packageDecl: Option[PackageDeclaration]): Ast = {

    val namespaceBlock = packageDecl match {
      case Some(decl) =>
        val packageName = decl.getName.toString
        val fullName    = filename + ":" + packageName
        NewNamespaceBlock()
          .name(packageName)
          .fullName(fullName)
      case None =>
        globalNamespaceBlock()
    }
    Ast(namespaceBlock.filename(absolutePath(filename)))
  }

  private def constructorSignature(
    constructor: ResolvedConstructorDeclaration,
    typeParamValues: ResolvedTypeParametersMap
  ): String = {
    val parameterTypes = calcParameterTypes(constructor, typeParamValues)

    composeMethodLikeSignature(TypeConstants.Void, parameterTypes)
  }

  private def methodSignature(method: ResolvedMethodDeclaration, typeParamValues: ResolvedTypeParametersMap): String = {
    val parameterTypes = calcParameterTypes(method, typeParamValues)

    val returnType =
      Try(method.getReturnType).toOption
        .map(returnType => typeInfoCalc.fullName(returnType, typeParamValues))
        .getOrElse(TypeConstants.UnresolvedType)

    composeMethodLikeSignature(returnType, parameterTypes)
  }

  private def calcParameterTypes(
    methodLike: ResolvedMethodLikeDeclaration,
    typeParamValues: ResolvedTypeParametersMap
  ): collection.Seq[String] = {
    val parameterTypes =
      Range(0, methodLike.getNumberOfParams)
        .flatMap { index =>
          Try(methodLike.getParam(index)).toOption
        }
        .map { param =>
          Try(param.getType).toOption
            .map(paramType => typeInfoCalc.fullName(paramType, typeParamValues))
            .getOrElse(TypeConstants.UnresolvedType)
        }

    parameterTypes
  }

  def getBindingTable(typeDecl: ResolvedReferenceTypeDeclaration): BindingTable = {
    val fullName = typeInfoCalc.fullName(typeDecl)
    bindingTableCache.getOrElseUpdate(
      fullName,
      createBindingTable(
        fullName,
        typeDecl,
        getBindingTable,
        methodSignature,
        new BindingTableAdapterForJavaparser(methodSignature)
      )
    )
  }

  def getLambdaBindingTable(lambdaBindingInfo: LambdaBindingInfo): BindingTable = {
    val fullName = lambdaBindingInfo.fullName

    bindingTableCache.getOrElseUpdate(
      fullName,
      createBindingTable(
        fullName,
        lambdaBindingInfo,
        getBindingTable,
        methodSignature,
        new BindingTableAdapterForLambdas()
      )
    )
  }

  def createBindingNodes(typeDeclNode: NewTypeDecl, bindingTable: BindingTable): Unit = {
    // We only sort to get stable output.
    val sortedEntries =
      bindingTable.getEntries.toBuffer.sortBy((entry: BindingTableEntry) => entry.name + entry.signature)

    sortedEntries.foreach { entry =>
      val bindingNode = NewBinding()
        .name(entry.name)
        .signature(entry.signature)
        .methodFullName(entry.implementingMethodFullName)

      diffGraph.addNode(bindingNode)
      diffGraph.addEdge(typeDeclNode, bindingNode, EdgeTypes.BINDS)
    }
  }

  private def astForTypeDeclMember(member: BodyDeclaration[_], astParentFullName: String): AstWithStaticInit = {
    member match {
      case constructor: ConstructorDeclaration =>
        val ast = astForConstructor(constructor)

        if (constructor.getComment.isPresent) {
          val commentAst = astForComment(constructor.getComment.get())
          AstWithStaticInit(Seq(commentAst, ast), Seq.empty)
        } else {
          AstWithStaticInit(ast)
        }

      case method: MethodDeclaration =>
        val ast = astForMethod(method)

        if (method.getComment.isPresent) {
          val commentAst = astForComment(method.getComment.get())
          AstWithStaticInit(Seq(commentAst, ast), Seq.empty)
        } else {
          AstWithStaticInit(ast)
        }

      case typeDeclaration: TypeDeclaration[_] =>
        AstWithStaticInit(astForTypeDecl(typeDeclaration, NodeTypes.TYPE_DECL, astParentFullName))

      case fieldDeclaration: FieldDeclaration =>
        val memberAsts = fieldDeclaration.getVariables.asScala.toList.map { variable =>
          astForFieldVariable(variable, fieldDeclaration)
        }

        val commentAndFieldAsts = if (fieldDeclaration.getComment.isPresent) {
          astForComment(fieldDeclaration.getComment.get()) +: memberAsts
        } else {
          memberAsts
        }

        val staticInitAsts = if (fieldDeclaration.isStatic) {
          val assignments = assignmentsForVarDecl(
            fieldDeclaration.getVariables.asScala.toList,
            line(fieldDeclaration),
            column(fieldDeclaration)
          )
          assignments
        } else {
          Nil
        }

        AstWithStaticInit(commentAndFieldAsts, staticInitAsts)

      case initDeclaration: InitializerDeclaration =>
        val stmts = initDeclaration.getBody.getStatements
        val asts  = stmts.asScala.flatMap(astsForStatement).toList
        val commentAsts = if (initDeclaration.getComment.isPresent) {
          Seq(astForComment(initDeclaration.getComment.get()))
        } else {
          Seq.empty
        }

        AstWithStaticInit(ast = commentAsts, staticInits = asts)

      case unhandled =>
        // AnnotationMemberDeclarations and InitializerDeclarations as children of typeDecls are the
        // expected cases.
        logger.info(s"Found unhandled typeDecl member ${unhandled.getClass} in file $filename")
        AstWithStaticInit.empty
    }
  }

  private def getTypeParameterMap(typeParameters: Iterable[TypeParameter]): Map[String, NewIdentifier] = {
    typeParameters.map { typeParam =>
      val name = typeParam.getNameAsString
      val typeFullName = typeParam.getTypeBound.asScala.headOption
        .flatMap { bound =>
          typeInfoCalc.fullName(bound)
        }
        .getOrElse(TypeConstants.Object)

      name -> NewIdentifier()
        .name(name)
        .typeFullName(typeFullName)
    }.toMap
  }

  private def getTypeParameterMap(node: Try[ResolvedReferenceTypeDeclaration]): Map[String, NewIdentifier] = {
    node match {
      case Success(resolved) =>
        resolved.getTypeParameters.asScala.map { typeParam =>
          val name = typeParam.getName
          val typeFullName = Try(typeParam.getUpperBound) match {
            case Success(upperBound) =>
              typeInfoCalc.fullName(upperBound)
            case Failure(_) =>
              TypeConstants.Object
          }
          // Incomplete identifier since these are never added to the AST. They're merely
          // used for the type info.
          name -> NewIdentifier()
            .name(name)
            .typeFullName(typeFullName)
        }.toMap

      case Failure(_) => Map.empty
    }
  }

  private def clinitAstsFromStaticInits(staticInits: Seq[Ast]): Option[Ast] = {
    if (staticInits.isEmpty) {
      None
    } else {
      // TODO: Get rid of magic strings
      val signature = s"${TypeConstants.Void}()"
      val fullName = scopeStack.getEnclosingTypeDecl
        .map { typeDecl =>
          s"${typeDecl.fullName}.<clinit>:$signature"
        }
        .getOrElse("")

      val methodNode = NewMethod()
        .name("<clinit>")
        .fullName(fullName)
        .signature(s"${TypeConstants.Void}()")

      val staticModifier = NewModifier()
        .modifierType(ModifierTypes.STATIC)
        .code(ModifierTypes.STATIC)

      val body = Ast(NewBlock()).withChildren(staticInits)

      val methodReturn = methodReturnNode(TypeConstants.Void, None, None, None)

      val methodAst =
        Ast(methodNode)
          .withChild(Ast(staticModifier))
          .withChild(body)
          .withChild(Ast(methodReturn))

      Some(methodAst)
    }
  }

  private def codeForTypeDecl(typ: TypeDeclaration[_], isInterface: Boolean): String = {
    val codeBuilder = new mutable.StringBuilder()
    if (typ.isPublic) {
      codeBuilder.append("public ")
    } else if (typ.isPrivate) {
      codeBuilder.append("private ")
    } else if (typ.isProtected) {
      codeBuilder.append("protected ")
    }

    if (typ.isStatic) {
      codeBuilder.append("static ")
    }

    val classPrefix = if (isInterface) "interface " else "class "
    codeBuilder.append(classPrefix)
    codeBuilder.append(typ.getNameAsString)

    codeBuilder.toString()
  }

  private def modifiersForTypeDecl(typ: TypeDeclaration[_], isInterface: Boolean): List[NewModifier] = {
    val accessModifierType = if (typ.isPublic) {
      Some(ModifierTypes.PUBLIC)
    } else if (typ.isPrivate) {
      Some(ModifierTypes.PRIVATE)
    } else if (typ.isProtected) {
      Some(ModifierTypes.PROTECTED)
    } else {
      None
    }
    val accessModifier = accessModifierType.map(NewModifier().modifierType(_))

    val abstractModifier = Option.when(isInterface || typ.getMethods.asScala.exists(_.isAbstract))(
      NewModifier().modifierType(ModifierTypes.ABSTRACT)
    )

    List(accessModifier, abstractModifier).flatten
  }

  private def createTypeDeclNode(
    typ: TypeDeclaration[_],
    astParentType: String,
    astParentFullName: String,
    isInterface: Boolean
  ): NewTypeDecl = {
    val baseTypeFullNames = if (typ.isClassOrInterfaceDeclaration) {
      val decl             = typ.asClassOrInterfaceDeclaration()
      val extendedTypes    = decl.getExtendedTypes.asScala
      val implementedTypes = decl.getImplementedTypes.asScala
      val maybeJavaObjectType = if (extendedTypes.isEmpty) {
        typeInfoCalc.registerType(TypeConstants.Object)
        Seq(TypeConstants.Object)
      } else {
        Seq()
      }
      maybeJavaObjectType ++ (extendedTypes ++ implementedTypes)
        .map(typ => typeInfoCalc.fullName(typ).getOrElse(TypeConstants.UnresolvedType))
        .toList
    } else {
      List.empty[String]
    }

    val resolvedType = Try(typ.resolve()).toOption
    val name         = resolvedType.map(typeInfoCalc.name).getOrElse(typ.getNameAsString)
    val typeFullName = resolvedType.map(typeInfoCalc.fullName).getOrElse(typ.getNameAsString)

    val code = codeForTypeDecl(typ, isInterface)

    NewTypeDecl()
      .name(name)
      .fullName(typeFullName)
      .lineNumber(line(typ))
      .columnNumber(column(typ))
      .inheritsFromTypeFullName(baseTypeFullNames)
      .filename(filename)
      .code(code)
      .astParentType(astParentType)
      .astParentFullName(astParentFullName)
  }

  private def astForTypeDecl(typ: TypeDeclaration[_], astParentType: String, astParentFullName: String): Ast = {
    val isInterface = typ match {
      case classDeclaration: ClassOrInterfaceDeclaration => classDeclaration.isInterface
      case _                                             => false
    }

    val typeDeclNode = createTypeDeclNode(typ, astParentType, astParentFullName, isInterface)

    scopeStack.pushNewScope(TypeDeclScope(typeDeclNode))

    val typeParameterMap = getTypeParameterMap(Try(typ.resolve()))
    typeParameterMap.foreach { case (identifier, node) =>
      scopeStack.addToScope(identifier, node)
    }

    val enumEntryAsts = if (typ.isEnumDeclaration) {
      typ.asEnumDeclaration().getEntries.asScala.map(astForEnumEntry).toList
    } else {
      List.empty
    }

    val staticInits: mutable.Buffer[Ast] = mutable.Buffer()
    val memberAsts = typ.getMembers.asScala.flatMap { member =>
      val astWithInits =
        astForTypeDeclMember(member, astParentFullName = NodeTypes.TYPE_DECL)
      staticInits.appendAll(astWithInits.staticInits)
      astWithInits.ast
    }

    val defaultConstructorAst = if (typ.getConstructors.isEmpty) {
      Some(astForDefaultConstructor())
    } else {
      None
    }

    val annotationAsts = typ.getAnnotations.asScala.map(astForAnnotationExpr)

    val clinitAst = clinitAstsFromStaticInits(staticInits.toSeq)

    val lambdaMethods = scopeStack.getLambdaMethodsInScope.toSeq

    val modifiers = modifiersForTypeDecl(typ, isInterface)

    val typeDeclAst = Ast(typeDeclNode)
      .withChildren(enumEntryAsts)
      .withChildren(memberAsts)
      .withChildren(defaultConstructorAst.toList)
      .withChildren(annotationAsts)
      .withChildren(clinitAst.toSeq)
      .withChildren(lambdaMethods)
      .withChildren(modifiers.map(Ast(_)))

    val defaultConstructorBindingEntry =
      defaultConstructorAst
        .flatMap(_.root)
        .collect { case defaultConstructor: NewNode with HasFullName with HasSignature =>
          defaultConstructor
        }
        .map { defaultConstructor =>
          BindingTableEntry("<init>", defaultConstructor.signature, defaultConstructor.fullName)
        }

    // Annotation declarations need no binding table as objects of this
    // typ never get called from user code.
    // Furthermore the parser library throws an exception when trying to
    // access e.g. the declared methods of an annotation declaration.
    if (!typ.isInstanceOf[AnnotationDeclaration]) {
      Try(typ.resolve()).toOption.foreach { resolvedTypeDecl =>
        val bindingTable = getBindingTable(resolvedTypeDecl)
        defaultConstructorBindingEntry.foreach(bindingTable.add)
        createBindingNodes(typeDeclNode, bindingTable)
      }
    }

    scopeStack.popScope()

    typeDeclAst
  }

  private def astForDefaultConstructor(): Ast = {
    val typeFullName = scopeStack.getEnclosingTypeDecl.map(_.fullName).getOrElse("<empty>")
    val constructorNode = NewMethod()
      .name("<init>")
      .fullName(s"$typeFullName.<init>:${TypeConstants.Void}()")
      .signature(s"${TypeConstants.Void}()")
      .filename(filename)
      .isExternal(false)

    val thisAst = thisAstForMethod(typeFullName, lineNumber = None)
    val bodyAst = Ast(NewBlock())

    val returnNode = methodReturnNode(TypeConstants.Void, None, None, None)
    val returnAst  = Ast(returnNode)

    val modifiers = List(
      Ast(NewModifier().modifierType(ModifierTypes.CONSTRUCTOR)),
      Ast(NewModifier().modifierType(ModifierTypes.PUBLIC))
    )

    Ast(constructorNode)
      .withChildren(modifiers)
      .withChild(thisAst)
      .withChild(bodyAst)
      .withChild(returnAst)
  }

  private def astForEnumEntry(entry: EnumConstantDeclaration): Ast = {
    val typeFullName =
      Try(entry.resolve().getType).toOption.map(typeInfoCalc.fullName).getOrElse(TypeConstants.UnresolvedType)
    val entryNode = NewMember()
      .lineNumber(line(entry))
      .columnNumber(column(entry))
      .code(entry.toString)
      .name(entry.getName.toString)
      .typeFullName(typeFullName)

    val args = entry.getArguments.asScala.map { argument =>
      val children = astsForExpression(argument, None)
      val callNode =
        NewCall()
          .name(s"$typeFullName.<init>")
          .methodFullName(s"$typeFullName.<init>")
          .dispatchType(DispatchTypes.STATIC_DISPATCH)
          .code(entry.toString)
          .lineNumber(line(entry))
          .columnNumber(column(entry))
      callAst(callNode, children)
    }

    Ast(entryNode).withChildren(args)
  }

  private def modifiersForFieldDeclaration(decl: FieldDeclaration): Seq[Ast] = {
    val staticModifier =
      Option.when(decl.isStatic)(NewModifier().modifierType(ModifierTypes.STATIC).code(ModifierTypes.STATIC))

    val accessModifier = if (decl.isPublic) {
      Some(NewModifier().modifierType(ModifierTypes.PUBLIC).code(ModifierTypes.PUBLIC))
    } else if (decl.isPrivate) {
      Some(NewModifier().modifierType(ModifierTypes.PRIVATE).code(ModifierTypes.PRIVATE))
    } else if (decl.isProtected) {
      Some(NewModifier().modifierType(ModifierTypes.PROTECTED).code(ModifierTypes.PROTECTED))
    } else {
      None
    }

    List(staticModifier, accessModifier).flatten.map(Ast(_))
  }

  private def astForFieldVariable(v: VariableDeclarator, fieldDeclaration: FieldDeclaration): Ast = {
    // TODO: Should be able to find expected type here
    val annotations = fieldDeclaration.getAnnotations
    val typeFullName =
      typeInfoCalc
        .fullName(v.getType)
        .orElse(scopeStack.getWildcardType(v.getTypeAsString))
        .getOrElse(TypeConstants.UnresolvedType)
    val name = v.getName.toString
    val memberNode =
      NewMember()
        .name(name)
        .typeFullName(typeFullName)
        .code(s"$typeFullName $name")
    val memberAst      = Ast(memberNode)
    val annotationAsts = annotations.asScala.map(astForAnnotationExpr)

    val fieldDeclModifiers = modifiersForFieldDeclaration(fieldDeclaration)

    val nodeTypeInfo = NodeTypeInfo(memberNode, isField = true, isStatic = fieldDeclaration.isStatic)
    scopeStack.addToScope(name, nodeTypeInfo)

    memberAst
      .withChildren(annotationAsts)
      .withChildren(fieldDeclModifiers)
  }

  private def astForConstructor(constructorDeclaration: ConstructorDeclaration): Ast = {
    scopeStack.pushNewScope(MethodScope(ExpectedType.Void))

    val parameterAsts  = astsForParameterList(constructorDeclaration.getParameters)
    val parameterTypes = parameterAsts.map(rootType(_).getOrElse(TypeConstants.UnresolvedType))
    val signature      = s"${TypeConstants.Void}(${parameterTypes.mkString(",")})"
    val fullName       = constructorFullName(scopeStack.getEnclosingTypeDecl, signature)

    val constructorNode = createPartialMethod(constructorDeclaration)
      .fullName(fullName)
      .signature(signature)

    parameterAsts.foreach { ast =>
      ast.root match {
        case Some(p: NewMethodParameterIn) => scopeStack.addToScope(p.name, p)
        case _                             => // This should never happen
      }
    }

    val typeFullName = scopeStack.getEnclosingTypeDecl.map(_.fullName).getOrElse(TypeConstants.UnresolvedType)
    val thisAst      = thisAstForMethod(typeFullName, line(constructorDeclaration))

    val bodyAst   = astForMethodBody(Some(constructorDeclaration.getBody))
    val returnAst = astForConstructorReturn(constructorDeclaration)

    val annotationAsts = constructorDeclaration.getAnnotations.asScala.map(astForAnnotationExpr)

    scopeStack.popScope()

    Ast(constructorNode)
      .withChild(thisAst)
      .withChildren(parameterAsts)
      .withChild(bodyAst)
      .withChild(returnAst)
      .withChildren(annotationAsts)
  }

  private def thisAstForMethod(typeFullName: String, lineNumber: Option[Integer]): Ast = {
    val node = NewMethodParameterIn()
      .name("this")
      .lineNumber(lineNumber)
      .code("this")
      .typeFullName(typeFullName)
      .dynamicTypeHintFullName(Seq(typeFullName))
      .evaluationStrategy(EvaluationStrategies.BY_SHARING)
      .index(0)
      .order(0)

    Ast(node)
  }

  private def convertAnnotationValueExpr(expr: Expression): Option[Ast] = {
    expr match {
      case arrayInit: ArrayInitializerExpr =>
        val arrayInitNode = NewArrayInitializer()
          .code(arrayInit.toString)
        val initElementAsts = arrayInit.getValues.asScala.toList.map { value =>
          convertAnnotationValueExpr(value)
        }

        setArgumentIndices(initElementAsts.flatten)

        val returnAst = initElementAsts.foldLeft(Ast(arrayInitNode)) {
          case (ast, Some(elementAst)) =>
            ast.withChild(elementAst)
          case (ast, _) => ast
        }
        Some(returnAst)

      case annotationExpr: AnnotationExpr =>
        Some(astForAnnotationExpr(annotationExpr))

      case literalExpr: LiteralExpr =>
        Some(astForAnnotationLiteralExpr(literalExpr))

      case _ =>
        logger.info(s"convertAnnotationValueExpr not yet implemented for ${expr.getClass}")
        None
    }
  }

  private def astForAnnotationLiteralExpr(literalExpr: LiteralExpr): Ast = {
    val valueNode =
      literalExpr match {
        case literal: StringLiteralExpr =>
          NewAnnotationLiteral()
            .code(literal.getValue)
            .name(literal.getValue)
        case literal: IntegerLiteralExpr =>
          NewAnnotationLiteral()
            .code(literal.getValue)
            .name(literal.getValue)
        case literal: BooleanLiteralExpr =>
          NewAnnotationLiteral()
            .code(java.lang.Boolean.toString(literal.getValue))
            .name(java.lang.Boolean.toString(literal.getValue))
        case literal: CharLiteralExpr =>
          NewAnnotationLiteral()
            .code(literal.getValue)
            .name(literal.getValue)
        case literal: DoubleLiteralExpr =>
          NewAnnotationLiteral()
            .code(literal.getValue)
            .name(literal.getValue)
        case literal: LongLiteralExpr =>
          NewAnnotationLiteral()
            .code(literal.getValue)
            .name(literal.getValue)
        case _: NullLiteralExpr =>
          NewAnnotationLiteral()
            .code("null")
            .name("null")
        case literal: TextBlockLiteralExpr =>
          NewAnnotationLiteral()
            .code(literal.getValue)
            .name(literal.getValue)
      }

    Ast(valueNode)
  }

  private def createAnnotationAssignmentAst(name: String, value: Expression, code: String): Ast = {
    val parameter = NewAnnotationParameter()
      .code(name)
    val rhs = convertAnnotationValueExpr(value)

    val assign = NewAnnotationParameterAssign()
      .code(code)

    val assignChildren = Ast(parameter) :: rhs.toList
    setArgumentIndices(assignChildren)

    Ast(assign)
      .withChild(Ast(parameter))
      .withChildren(rhs.toSeq)
  }

  private def expressionReturnTypeFullName(expr: Expression): Option[String] = {
    Try(expr.calculateResolvedType()) match {
      case Success(resolveType) =>
        Some(typeInfoCalc.fullName(resolveType))
      case Failure(_) =>
        expr match {
          case namedExpr: NodeWithName[_] => scopeStack.lookupVariableType(namedExpr.getNameAsString)

          case namedExpr: NodeWithSimpleName[_] => scopeStack.lookupVariableType(namedExpr.getNameAsString)

          // JavaParser doesn't handle literals well for some reason
          case _: BooleanLiteralExpr   => Some("boolean")
          case _: CharLiteralExpr      => Some("char")
          case _: DoubleLiteralExpr    => Some("double")
          case _: IntegerLiteralExpr   => Some("int")
          case _: LongLiteralExpr      => Some("long")
          case _: NullLiteralExpr      => Some("null")
          case _: StringLiteralExpr    => Some("java.lang.String")
          case _: TextBlockLiteralExpr => Some("java.lang.String")
          case _                       => None
        }
    }
  }

  private def createAnnotationNode(annotationExpr: AnnotationExpr): NewAnnotation = {
    NewAnnotation()
      .code(annotationExpr.toString)
      .name(annotationExpr.getName.getIdentifier)
      .fullName(expressionReturnTypeFullName(annotationExpr).getOrElse(TypeConstants.UnresolvedType))
  }

  private def astForAnnotationExpr(annotationExpr: AnnotationExpr): Ast = {
    annotationExpr match {
      case _: MarkerAnnotationExpr =>
        Ast(createAnnotationNode(annotationExpr))
      case normal: NormalAnnotationExpr =>
        val annotationAst = Ast(createAnnotationNode(annotationExpr))
        val assignmentAsts = normal.getPairs.asScala.map { pair =>
          createAnnotationAssignmentAst(pair.getName.getIdentifier, pair.getValue, pair.toString)
        }
        assignmentAsts.foldLeft(annotationAst) { case (ast, assignmentAst) =>
          ast.withChild(assignmentAst)
        }
      case single: SingleMemberAnnotationExpr =>
        val annotationAst = Ast(createAnnotationNode(annotationExpr))
        annotationAst.withChild(
          createAnnotationAssignmentAst("value", single.getMemberValue, single.getMemberValue.toString)
        )
    }
  }

  private def getMethodFullName(typeDecl: Option[NewTypeDecl], methodName: String, maybeSignature: Option[String]) = {
    val typeName  = typeDecl.map(_.fullName).getOrElse(TypeConstants.UnresolvedType)
    val signature = maybeSignature.getOrElse(TypeConstants.UnresolvedSignature)

    composeMethodFullName(typeName, methodName, signature)
  }

  private def modifierAstsForMethod(methodDeclaration: MethodDeclaration): Seq[Ast] = {
    val isInterfaceMethod         = scopeStack.getEnclosingTypeDecl.exists(_.code.contains("interface "))
    val isAbstractMethod          = methodDeclaration.isAbstract || (isInterfaceMethod && !methodDeclaration.isDefault)
    val abstractModifier          = Option.when(isAbstractMethod)(NewModifier().modifierType(ModifierTypes.ABSTRACT))
    val staticVirtualModifierType = if (methodDeclaration.isStatic) ModifierTypes.STATIC else ModifierTypes.VIRTUAL
    val staticVirtualModifier     = Some(NewModifier().modifierType(staticVirtualModifierType))
    val accessModifierType = if (methodDeclaration.isPublic) {
      Some(ModifierTypes.PUBLIC)
    } else if (methodDeclaration.isPrivate) {
      Some(ModifierTypes.PRIVATE)
    } else if (isInterfaceMethod) {
      // TODO: more robust interface check
      Some(ModifierTypes.PUBLIC)
    } else {
      None
    }
    val accessModifier = accessModifierType.map(NewModifier().modifierType(_))

    List(accessModifier, abstractModifier, staticVirtualModifier).flatten.map(Ast(_))
  }

  private def astForMethod(methodDeclaration: MethodDeclaration): Ast = {
    val expectedReturnType = Try(
      symbolResolver.toResolvedType(methodDeclaration.getType, classOf[ResolvedType])
    ).toOption
    val expectedReturnTypeName = expectedReturnType.map(typeInfoCalc.fullName)

    scopeStack.pushNewScope(
      MethodScope(ExpectedType(expectedReturnTypeName.getOrElse(TypeConstants.UnresolvedType), expectedReturnType))
    )

    val typeParamMap = getTypeParameterMap(methodDeclaration.getTypeParameters.asScala)
    typeParamMap.foreach { case (identifier, typeParam) =>
      scopeStack.addToScope(identifier, typeParam)
    }

    val parameterAsts = astsForParameterList(methodDeclaration.getParameters)

    val returnType =
      expectedReturnTypeName
        // This duplicates some code from TypeInfoCalculator.nameOrFullName, but provides a way to calculate
        // the expected return type above, re-use that here and avoid attempting to resolve unresolvable
        // types twice.
        .orElse(scopeStack.lookupVariableType(methodDeclaration.getTypeAsString))
        .orElse(scopeStack.getWildcardType(methodDeclaration.getTypeAsString))

    val parameterTypes = parameterAsts.map(rootType(_).getOrElse(TypeConstants.UnresolvedType))
    val signature = returnType map { typ =>
      s"$typ(${parameterTypes.mkString(",")})"
    }
    val methodFullName =
      getMethodFullName(scopeStack.getEnclosingTypeDecl, methodDeclaration.getNameAsString, signature)

    val methodNode = createPartialMethod(methodDeclaration)
      .fullName(methodFullName)
      .signature(signature.getOrElse(""))

    val thisAst = if (methodDeclaration.isStatic) {
      Seq()
    } else {
      val typeFullName = scopeStack.getEnclosingTypeDecl.map(_.fullName).getOrElse(TypeConstants.UnresolvedType)
      Seq(thisAstForMethod(typeFullName, line(methodDeclaration)))
    }

    val bodyAst   = astForMethodBody(methodDeclaration.getBody.toScala)
    val returnAst = astForMethodReturn(methodDeclaration)

    val annotationAsts = methodDeclaration.getAnnotations.asScala.map(astForAnnotationExpr)

    val modifierAsts = modifierAstsForMethod(methodDeclaration)

    scopeStack.popScope()

    Ast(methodNode)
      .withChildren(thisAst)
      .withChildren(parameterAsts)
      .withChild(bodyAst)
      .withChildren(annotationAsts)
      .withChildren(modifierAsts)
      .withChild(returnAst)
  }

  private def astForMethodReturn(methodDeclaration: MethodDeclaration): Ast = {
    val typeFullName = typeInfoCalc.fullName(methodDeclaration.getType).getOrElse(TypeConstants.UnresolvedType)
    Ast(methodReturnNode(typeFullName, None, line(methodDeclaration.getType), column(methodDeclaration.getType)))
  }

  private def astForConstructorReturn(constructorDeclaration: ConstructorDeclaration): Ast = {
    val line   = constructorDeclaration.getEnd.map(x => Integer.valueOf(x.line)).toScala
    val column = constructorDeclaration.getEnd.map(x => Integer.valueOf(x.column)).toScala
    val node   = methodReturnNode(TypeConstants.Void, None, line, column)
    Ast(node)
  }

  /** Constructor and Method declarations share a lot of fields, so this method adds the fields they have in common.
    * `fullName` and `signature` are omitted
    */
  private def createPartialMethod(declaration: CallableDeclaration[_]): NewMethod = {
    val code         = declaration.getDeclarationAsString.trim
    val columnNumber = declaration.getBegin.map(x => Integer.valueOf(x.column)).toScala
    val endLine      = declaration.getEnd.map(x => Integer.valueOf(x.line)).toScala
    val endColumn    = declaration.getEnd.map(x => Integer.valueOf(x.column)).toScala

    val methodNode = NewMethod()
      .name(declaration.getNameAsString)
      .code(code)
      .isExternal(false)
      .filename(filename)
      .lineNumber(line(declaration))
      .columnNumber(columnNumber)
      .lineNumberEnd(endLine)
      .columnNumberEnd(endColumn)

    methodNode
  }

  private def astForMethodBody(body: Option[BlockStmt]): Ast = {
    body match {
      case Some(b) => astForBlockStatement(b)
      case None    => Ast(NewBlock())
    }
  }

  def astsForLabeledStatement(stmt: LabeledStmt): Seq[Ast] = {
    val jumpTargetAst = Ast(NewJumpTarget().name(stmt.getLabel.toString))
    val stmtAst       = astsForStatement(stmt.getStatement).toList

    jumpTargetAst :: stmtAst
  }

  def astForThrow(stmt: ThrowStmt): Ast = {
    val throwNode = NewCall()
      .name("<operator>.throw")
      .methodFullName("<operator>.throw")
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))
      .code(stmt.toString())
      .dispatchType(DispatchTypes.STATIC_DISPATCH)

    val args = astsForExpression(stmt.getExpression, None)

    callAst(throwNode, args)
  }

  def astForCatchClause(catchClause: CatchClause): Ast = {
    astForBlockStatement(catchClause.getBody)
  }

  def astForTry(stmt: TryStmt): Ast = {
    val tryNode = NewControlStructure()
      .controlStructureType(ControlStructureTypes.TRY)
      .code("try")
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))

    val tryAst    = astForBlockStatement(stmt.getTryBlock, codeStr = "try")
    val catchAsts = stmt.getCatchClauses.asScala.map(astForCatchClause)
    val catchBlock = Ast(NewBlock().code("catch"))
      .withChildren(catchAsts)
    val finallyAst =
      stmt.getFinallyBlock.toScala.map(astForBlockStatement(_, "finally")).toList

    Ast(tryNode)
      .withChild(tryAst)
      .withChild(catchBlock)
      .withChildren(finallyAst)
  }

  private def astsForStatement(statement: Statement): Seq[Ast] = {
    // TODO: Implement missing handlers
    // case _: LocalClassDeclarationStmt  => Seq()
    // case _: LocalRecordDeclarationStmt => Seq()
    // case _: YieldStmt                  => Seq()
    val asts = statement match {
      case x: ExplicitConstructorInvocationStmt =>
        Seq(astForExplicitConstructorInvocation(x))
      case x: AssertStmt       => Seq(astForAssertStatement(x))
      case x: BlockStmt        => Seq(astForBlockStatement(x))
      case x: BreakStmt        => Seq(astForBreakStatement(x))
      case x: ContinueStmt     => Seq(astForContinueStatement(x))
      case x: DoStmt           => Seq(astForDo(x))
      case _: EmptyStmt        => Seq() // Intentionally skipping this
      case x: ExpressionStmt   => astsForExpression(x.getExpression, Some(ExpectedType.Void))
      case x: ForEachStmt      => astForForEach(x)
      case x: ForStmt          => Seq(astForFor(x))
      case x: IfStmt           => Seq(astForIf(x))
      case x: LabeledStmt      => astsForLabeledStatement(x)
      case x: ReturnStmt       => Seq(astForReturnNode(x))
      case x: SwitchStmt       => Seq(astForSwitchStatement(x))
      case x: SynchronizedStmt => Seq(astForSynchronizedStatement(x))
      case x: ThrowStmt        => Seq(astForThrow(x))
      case x: TryStmt          => Seq(astForTry(x))
      case x: WhileStmt        => Seq(astForWhile(x))
      case x =>
        logger.warn(s"Attempting to generate AST for unknown statement $x")
        Seq(unknownAst(x))
    }
    if (statement.getComment.isPresent) {
      astForComment(statement.getComment.get()) +: asts
    } else {
      asts
    }
  }

  private def astForElse(maybeStmt: Option[Statement]): Option[Ast] = {
    maybeStmt.map { stmt =>
      val elseAsts = astsForStatement(stmt)

      val elseNode =
        NewControlStructure()
          .controlStructureType(ControlStructureTypes.ELSE)
          .lineNumber(line(stmt))
          .columnNumber(column(stmt))
          .code("else")

      Ast(elseNode).withChildren(elseAsts)
    }
  }

  def astForIf(stmt: IfStmt): Ast = {
    val ifNode =
      NewControlStructure()
        .controlStructureType(ControlStructureTypes.IF)
        .lineNumber(line(stmt))
        .columnNumber(column(stmt))
        .code(s"if (${stmt.getCondition.toString})")

    val conditionAst =
      astsForExpression(stmt.getCondition, Some(ExpectedType.Boolean)).headOption.toList

    val thenAsts = astsForStatement(stmt.getThenStmt)
    val elseAst  = astForElse(stmt.getElseStmt.toScala).toList

    val ast = Ast(ifNode)
      .withChildren(conditionAst)
      .withChildren(thenAsts)
      .withChildren(elseAst)

    conditionAst.flatMap(_.root.toList) match {
      case r :: Nil =>
        ast.withConditionEdge(ifNode, r)
      case _ =>
        ast
    }
  }

  def astForWhile(stmt: WhileStmt): Ast = {
    val whileNode =
      NewControlStructure()
        .controlStructureType(ControlStructureTypes.WHILE)
        .lineNumber(line(stmt))
        .columnNumber(column(stmt))
        .code(s"while (${stmt.getCondition.toString})")

    val conditionAst =
      astsForExpression(stmt.getCondition, Some(ExpectedType.Boolean)).headOption.toList
    val stmtAsts = astsForStatement(stmt.getBody)

    val ast = Ast(whileNode)
      .withChildren(conditionAst)
      .withChildren(stmtAsts)

    conditionAst.flatMap(_.root.toList) match {
      case r :: Nil =>
        ast.withConditionEdge(whileNode, r)
      case _ =>
        ast
    }
  }

  def astForDo(stmt: DoStmt): Ast = {
    val doNode =
      NewControlStructure().controlStructureType(ControlStructureTypes.DO)
    val conditionAst =
      astsForExpression(stmt.getCondition, Some(ExpectedType.Boolean)).headOption.toList
    val stmtAsts = astsForStatement(stmt.getBody)
    controlStructureAst(doNode, conditionAst.headOption, stmtAsts.toList, placeConditionLast = true)
  }

  def astForBreakStatement(stmt: BreakStmt): Ast = {
    val node = NewControlStructure()
      .controlStructureType(ControlStructureTypes.BREAK)
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))
      .code(stmt.toString)
    Ast(node)
  }

  def astForContinueStatement(stmt: ContinueStmt): Ast = {
    val node = NewControlStructure()
      .controlStructureType(ControlStructureTypes.CONTINUE)
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))
      .code(stmt.toString)
    Ast(node)
  }

  private def getForCode(stmt: ForStmt): String = {
    val init    = stmt.getInitialization.asScala.map(_.toString).mkString(", ")
    val compare = stmt.getCompare.toScala.map(_.toString)
    val update  = stmt.getUpdate.asScala.map(_.toString).mkString(", ")
    s"for ($init; $compare; $update)"
  }
  def astForFor(stmt: ForStmt): Ast = {
    val forNode =
      NewControlStructure()
        .controlStructureType(ControlStructureTypes.FOR)
        .code(getForCode(stmt))
        .lineNumber(line(stmt))
        .columnNumber(column(stmt))

    val initAsts =
      stmt.getInitialization.asScala.flatMap(astsForExpression(_, expectedType = None))

    val compareAsts = stmt.getCompare.toScala.toList.flatMap {
      astsForExpression(_, Some(ExpectedType.Boolean))
    }

    val updateAsts = stmt.getUpdate.asScala.toList.flatMap {
      astsForExpression(_, None)
    }

    val stmtAsts =
      astsForStatement(stmt.getBody)

    val ast = Ast(forNode)
      .withChildren(initAsts)
      .withChildren(compareAsts)
      .withChildren(updateAsts)
      .withChildren(stmtAsts)

    compareAsts.flatMap(_.root) match {
      case c :: Nil =>
        ast.withConditionEdge(forNode, c)
      case _ => ast
    }
  }

  private def iterableAssignAstsForNativeForEach(
    iterableExpression: Expression,
    iterableType: String
  ): (NewLocal, Seq[Ast]) = {
    val lineNo       = line(iterableExpression)
    val expectedType = Some(ExpectedType(iterableType))

    val iterableAst = astsForExpression(iterableExpression, expectedType = expectedType) match {
      case Nil =>
        logger.error(s"Could not create AST for iterable expr $iterableExpression: $filename:l$lineNo")
        Ast()

      case iterableAst :: Nil => iterableAst

      case iterableAsts =>
        logger.warn(
          s"Found multiple ASTS for iterable expr $iterableExpression: $filename:l$lineNo\nDropping all but the first!"
        )
        iterableAsts.head
    }

    val iterableName = nextIterableName()
    val iterableLocalNode =
      NewLocal()
        .name(iterableName)
        .code(iterableName)
        .typeFullName(iterableType)
        .lineNumber(lineNo)
    val iterableLocalAst = Ast(iterableLocalNode)

    val iterableAssignNode =
      operatorCallNode(Operators.assignment, code = "", line = lineNo, typeFullName = Some(iterableType))
    val iterableAssignIdentifier =
      NewIdentifier()
        .name(iterableName)
        .code(iterableName)
        .typeFullName(iterableType)
        .lineNumber(lineNo)
    val iterableAssignArgs = List(Ast(iterableAssignIdentifier), iterableAst)
    val iterableAssignAst =
      callAst(iterableAssignNode, iterableAssignArgs)
        .withRefEdge(iterableAssignIdentifier, iterableLocalNode)

    (iterableLocalNode, List(iterableLocalAst, iterableAssignAst))
  }

  private def nativeForEachIdxLocalNode(lineNo: Option[Integer]): NewLocal = {
    val idxName = nextIndexName()
    val idxLocal =
      NewLocal()
        .name(idxName)
        .typeFullName(TypeConstants.Int)
        .code(idxName)
        .lineNumber(lineNo)
    scopeStack.addToScope(idxName, idxLocal)
    idxLocal
  }

  private def nativeForEachIdxInitializerAst(lineNo: Option[Integer], idxLocal: NewLocal): Ast = {
    val idxName = idxLocal.name
    val idxInitializerCallNode = operatorCallNode(
      Operators.assignment,
      code = s"int $idxName = 0",
      line = lineNo,
      typeFullName = Some(TypeConstants.Int)
    )
    val idxIdentifierArg = identifierFromNamedVarType(idxLocal, lineNo)
    val zeroLiteral =
      NewLiteral()
        .code("0")
        .typeFullName(TypeConstants.Int)
        .lineNumber(lineNo)
    val idxInitializerArgAsts = List(Ast(idxIdentifierArg), Ast(zeroLiteral))
    callAst(idxInitializerCallNode, idxInitializerArgAsts)
      .withRefEdge(idxIdentifierArg, idxLocal)
  }

  private def nativeForEachCompareAst(
    lineNo: Option[Integer],
    iterableLocal: NamedVariableNodeType,
    idxLocal: NewLocal
  ): Ast = {
    val idxName      = idxLocal.name
    val iterableName = iterableLocal.name

    val compareNode = operatorCallNode(
      Operators.lessThan,
      code = s"$idxName < $iterableName.length",
      typeFullName = Some(TypeConstants.Boolean),
      line = lineNo
    )
    val comparisonIdxIdentifier = identifierFromNamedVarType(idxLocal, lineNo)
    val comparisonFieldAccess = operatorCallNode(
      Operators.fieldAccess,
      code = s"$iterableName.length",
      typeFullName = Some(TypeConstants.Int),
      line = lineNo
    )
    val fieldAccessIdentifier      = identifierFromNamedVarType(iterableLocal, lineNo)
    val fieldAccessFieldIdentifier = fieldIdentifierNode("length", lineNo)
    val fieldAccessArgs            = List(fieldAccessIdentifier, fieldAccessFieldIdentifier).map(Ast(_))
    val fieldAccessAst             = callAst(comparisonFieldAccess, fieldAccessArgs)
    val compareArgs                = List(Ast(comparisonIdxIdentifier), fieldAccessAst)

    callAst(compareNode, compareArgs)
      .withRefEdge(comparisonIdxIdentifier, idxLocal)
      .withRefEdge(fieldAccessIdentifier, iterableLocal)
  }

  private def nativeForEachIncrementAst(lineNo: Option[Integer], idxLocal: NewLocal): Ast = {
    val incrementNode = operatorCallNode(
      Operators.postIncrement,
      code = s"${idxLocal.name}++",
      typeFullName = Some(TypeConstants.Int),
      line = lineNo
    )
    val incrementArg    = identifierFromNamedVarType(idxLocal, lineNo)
    val incrementArgAst = Ast(incrementArg)
    callAst(incrementNode, List(incrementArgAst))
      .withRefEdge(incrementArg, idxLocal)
  }

  private def variableLocalForForEachBody(stmt: ForEachStmt): NewLocal = {
    val lineNo = line(stmt)
    // Create item local
    val maybeVariable = stmt.getVariable.getVariables.asScala.toList match {
      case Nil =>
        logger.error(s"ForEach statement has empty variable list: $filename$lineNo")
        None
      case variable :: Nil => Some(variable)
      case variable :: _ =>
        logger.warn(s"ForEach statement defines multiple variables. Dropping all but the first: $filename$lineNo")
        Some(variable)
    }

    val partialLocalNode = NewLocal().lineNumber(lineNo)

    maybeVariable match {
      case Some(variable) =>
        val typeFullName = typeInfoCalc.fullName(variable.getType).getOrElse(TypeConstants.UnresolvedType)
        val localNode = partialLocalNode
          .name(variable.getNameAsString)
          .code(variable.getNameAsString)
          .typeFullName(typeFullName)
        scopeStack.addToScope(localNode.name, localNode)
        localNode

      case None =>
        // Returning partialLocalNode here is fine since getting to this case means everything is
        // broken anyways :)
        partialLocalNode
    }
  }

  private def variableAssignForNativeForEachBody(
    variableLocal: NewLocal,
    idxLocal: NewLocal,
    iterableNode: NamedVariableNodeType
  ): Ast = {
    // Everything will be on the same line as the `for` statement, but this is the most useful
    // solution for debugging.
    val lineNo = variableLocal.lineNumber
    val varAssignNode = assignmentNode()
      .lineNumber(lineNo)
      .typeFullName(variableLocal.typeFullName)

    val targetNode = identifierFromNamedVarType(variableLocal, lineNo)

    val indexAccess = indexAccessNode()
      .lineNumber(lineNo)
      .typeFullName(iterableNode.typeFullName.replaceAll(raw"\[]", ""))

    val indexAccessIdentifier = identifierFromNamedVarType(iterableNode, lineNo)
    val indexAccessIndex      = identifierFromNamedVarType(idxLocal, lineNo)

    val indexAccessArgsAsts = List(indexAccessIdentifier, indexAccessIndex).map(Ast(_))
    val indexAccessAst      = callAst(indexAccess, indexAccessArgsAsts)

    val assignArgsAsts = List(Ast(targetNode), indexAccessAst)
    callAst(varAssignNode, assignArgsAsts)
      .withRefEdge(targetNode, variableLocal)
      .withRefEdge(indexAccessIdentifier, iterableNode)
      .withRefEdge(indexAccessIndex, idxLocal)
  }

  private def nativeForEachBodyAst(stmt: ForEachStmt, idxLocal: NewLocal, iterableNode: NamedVariableNodeType): Ast = {
    val variableLocal     = variableLocalForForEachBody(stmt)
    val variableLocalAst  = Ast(variableLocal)
    val variableAssignAst = variableAssignForNativeForEachBody(variableLocal, idxLocal, iterableNode)

    stmt.getBody match {
      case block: BlockStmt =>
        astForBlockStatement(block, prefixAsts = List(variableLocalAst, variableAssignAst))

      case stmt =>
        val stmtAsts  = astsForStatement(stmt)
        val blockNode = NewBlock().lineNumber(variableLocal.lineNumber)
        Ast(blockNode)
          .withChild(variableLocalAst)
          .withChild(variableAssignAst)
          .withChildren(stmtAsts)
    }
  }

  private def identifierFromNamedVarType(local: NamedVariableNodeType, lineNumber: Option[Integer]): NewIdentifier = {
    NewIdentifier()
      .name(local.name)
      .code(local.name)
      .typeFullName(local.typeFullName)
      .lineNumber(lineNumber)
  }

  private def astsForNativeForEach(stmt: ForEachStmt, iterableType: String): Seq[Ast] = {

    // This is ugly, but for a case like `for (int x : new int[] { ... })` this creates a new LOCAL
    // with the assignment `int[] $iterLocal0 = new int[] { ... }` before the FOR loop.
    val (iterableSourceNode, tempIterableInitAsts) = stmt.getIterable match {
      case nameExpr: NameExpr =>
        scopeStack.lookupVariable(nameExpr.getNameAsString) match {
          // If this is not the case, then the code is broken (iterable not in scope).
          case Some(NodeTypeInfo(node: NamedVariableNodeType, _, _)) => (node, Nil)
          case _ => iterableAssignAstsForNativeForEach(nameExpr, iterableType)
        }
      case iterableExpr => iterableAssignAstsForNativeForEach(iterableExpr, iterableType)
    }

    val forNode = NewControlStructure()
      .controlStructureType(ControlStructureTypes.FOR)

    val lineNo = line(stmt)

    val idxLocal          = nativeForEachIdxLocalNode(lineNo)
    val idxInitializerAst = nativeForEachIdxInitializerAst(lineNo, idxLocal)
    val compareAst        = nativeForEachCompareAst(lineNo, iterableSourceNode, idxLocal)
    val incrementAst      = nativeForEachIncrementAst(lineNo, idxLocal)
    val bodyAst           = nativeForEachBodyAst(stmt, idxLocal, iterableSourceNode)

    val forAst = Ast(forNode)
      .withChild(Ast(idxLocal))
      .withChild(idxInitializerAst)
      .withChild(compareAst)
      .withChild(incrementAst)
      .withChild(bodyAst)
      .withConditionEdges(forNode, compareAst.root.toList)

    tempIterableInitAsts ++ Seq(forAst)
  }

  private def iteratorLocalForForEach(lineNumber: Option[Integer]): NewLocal = {
    val iteratorLocalName = nextIterableName()
    NewLocal()
      .name(iteratorLocalName)
      .code(iteratorLocalName)
      .typeFullName(TypeConstants.Iterator)
      .lineNumber(lineNumber)
  }

  private def iteratorAssignAstForForEach(
    iterExpr: Expression,
    iteratorLocalNode: NewLocal,
    iterableType: String,
    lineNo: Option[Integer]
  ): Ast = {
    val iteratorAssignNode =
      operatorCallNode(Operators.assignment, code = "", typeFullName = Some(TypeConstants.Iterator), line = lineNo)
    val iteratorAssignIdentifier = identifierFromNamedVarType(iteratorLocalNode, lineNo)
    val iteratorMethodName       = "iterator"
    val iteratorMethodSignature  = composeMethodLikeSignature(TypeConstants.Iterator, parameterTypes = Nil)
    val iteratorMethodFullName   = composeMethodFullName(iterableType, iteratorMethodName, iteratorMethodSignature)
    // TODO: This is the only section that needs to be updated for unified native/collection foreach representations.
    val iteratorCallNode =
      NewCall()
        .name(iteratorMethodName)
        .methodFullName(iteratorMethodFullName)
        .signature(iteratorMethodSignature)
        .typeFullName(TypeConstants.Iterator)
        .dispatchType(DispatchTypes.DYNAMIC_DISPATCH)
        .code(iteratorMethodFullName)
        .lineNumber(lineNo)

    val actualIteratorAst = astsForExpression(iterExpr, expectedType = None).toList match {
      case Nil =>
        logger.warn(s"Could not create receiver ast for iterator $iterExpr")
        None

      case ast :: Nil => Some(ast)

      case ast :: _ =>
        logger.warn(s"Created multiple receiver asts for $iterExpr. Dropping all but the first.")
        Some(ast)
    }

    val iteratorCallAst =
      callAst(iteratorCallNode, receiver = actualIteratorAst, withRecvArgEdge = true)

    callAst(iteratorAssignNode, List(Ast(iteratorAssignIdentifier), iteratorCallAst))
      .withRefEdge(iteratorAssignIdentifier, iteratorLocalNode)
  }

  private def hasNextCallAstForForEach(iteratorLocalNode: NewLocal, lineNo: Option[Integer]): Ast = {
    val hasNextCallName      = "hasNext"
    val hasNextCallSignature = composeMethodLikeSignature(TypeConstants.Boolean, parameterTypes = Nil)
    val hasNextCallFullName  = composeMethodFullName(TypeConstants.Iterator, hasNextCallName, hasNextCallSignature)
    val iteratorHasNextCallNode =
      NewCall()
        .name(hasNextCallName)
        .methodFullName(hasNextCallFullName)
        .signature(hasNextCallSignature)
        .typeFullName(TypeConstants.Boolean)
        .dispatchType(DispatchTypes.DYNAMIC_DISPATCH)
        .code(hasNextCallFullName)
        .lineNumber(lineNo)
    val iteratorHasNextCallReceiver = identifierFromNamedVarType(iteratorLocalNode, lineNo)

    callAst(iteratorHasNextCallNode, receiver = Some(Ast(iteratorHasNextCallReceiver)), withRecvArgEdge = true)
      .withRefEdge(iteratorHasNextCallReceiver, iteratorLocalNode)
  }

  private def astForIterableForEachItemAssign(iteratorLocalNode: NewLocal, variableLocal: NewLocal): Ast = {
    val lineNo          = variableLocal.lineNumber
    val forVariableType = variableLocal.typeFullName
    val varLocalAssignNode =
      assignmentNode()
        .typeFullName(forVariableType)
        .lineNumber(lineNo)
    val varLocalAssignIdentifier = identifierFromNamedVarType(variableLocal, lineNo)

    val iterNextCallName      = "next"
    val iterNextCallSignature = composeMethodLikeSignature(TypeConstants.Object, parameterTypes = Nil)
    val iterNextCallFullName  = composeMethodFullName(TypeConstants.Iterator, iterNextCallName, iterNextCallSignature)
    val iterNextCallNode =
      NewCall()
        .name(iterNextCallName)
        .methodFullName(iterNextCallFullName)
        .signature(iterNextCallSignature)
        .typeFullName(TypeConstants.Object)
        .dispatchType(DispatchTypes.DYNAMIC_DISPATCH)
        .code(iterNextCallFullName)
        .lineNumber(lineNo)
    val iterNextCallReceiver = identifierFromNamedVarType(iteratorLocalNode, lineNo)
    val iterNextCallAst =
      callAst(iterNextCallNode, receiver = Some(Ast(iterNextCallReceiver)), withRecvArgEdge = true)
        .withRefEdge(iterNextCallReceiver, iteratorLocalNode)

    callAst(varLocalAssignNode, List(Ast(varLocalAssignIdentifier), iterNextCallAst))
      .withRefEdge(varLocalAssignIdentifier, variableLocal)
  }

  private def astForIterableForEach(stmt: ForEachStmt, maybeTypeFullName: Option[String]): Seq[Ast] = {
    val lineNo       = line(stmt)
    val iterableType = maybeTypeFullName.getOrElse(TypeConstants.UnresolvedType)

    val iteratorLocalNode = iteratorLocalForForEach(lineNo)
    val iteratorAssignAst =
      iteratorAssignAstForForEach(stmt.getIterable, iteratorLocalNode, iterableType, lineNo)
    val iteratorHasNextCallAst = hasNextCallAstForForEach(iteratorLocalNode, lineNo)
    val variableLocal          = variableLocalForForEachBody(stmt)
    val variableAssignAst      = astForIterableForEachItemAssign(iteratorLocalNode, variableLocal)

    val bodyPrefixAsts = Seq(Ast(variableLocal), variableAssignAst)
    val bodyAst = stmt.getBody match {
      case block: BlockStmt =>
        astForBlockStatement(block, prefixAsts = bodyPrefixAsts)

      case bodyStmt =>
        val bodyBlockNode = NewBlock().lineNumber(lineNo)
        val bodyStmtAsts  = astsForStatement(bodyStmt)
        Ast(bodyBlockNode)
          .withChildren(bodyPrefixAsts)
          .withChildren(bodyStmtAsts)
    }

    val forNode =
      NewControlStructure()
        .controlStructureType(ControlStructureTypes.WHILE)
        .code(ControlStructureTypes.FOR)
        .lineNumber(lineNo)
        .columnNumber(column(stmt))

    val forAst = controlStructureAst(forNode, Some(iteratorHasNextCallAst), List(bodyAst))

    Seq(Ast(iteratorLocalNode), iteratorAssignAst, forAst)
  }

  def astForForEach(stmt: ForEachStmt): Seq[Ast] = {
    scopeStack.pushNewScope(BlockScope)

    val ast = expressionReturnTypeFullName(stmt.getIterable) match {
      case Some(typeFullName) if typeFullName.endsWith("[]") =>
        astsForNativeForEach(stmt, typeFullName)

      case maybeType =>
        astForIterableForEach(stmt, maybeType)
    }

    scopeStack.popScope()
    ast
  }

  def astForSwitchStatement(stmt: SwitchStmt): Ast = {
    val switchNode =
      NewControlStructure()
        .controlStructureType(ControlStructureTypes.SWITCH)
        .code(s"switch(${stmt.getSelector.toString})")

    val selectorAsts = astsForExpression(stmt.getSelector, None)
    val selectorNode = selectorAsts.head.root.get

    val entryAsts = stmt.getEntries.asScala.flatMap(astForSwitchEntry)

    val switchBodyAst = Ast(NewBlock()).withChildren(entryAsts)

    Ast(switchNode)
      .withChildren(selectorAsts)
      .withChild(switchBodyAst)
      .withConditionEdge(switchNode, selectorNode)
  }

  private def astForSynchronizedStatement(stmt: SynchronizedStmt): Ast = {
    val parentNode =
      NewBlock()
        .lineNumber(line(stmt))
        .columnNumber(column(stmt))

    val modifier = Ast(NewModifier().modifierType("SYNCHRONIZED"))

    val exprAsts = astsForExpression(stmt.getExpression, None)
    val bodyAst  = astForBlockStatement(stmt.getBody)

    Ast(parentNode)
      .withChild(modifier)
      .withChildren(exprAsts)
      .withChild(bodyAst)
  }

  private def astsForSwitchCases(entry: SwitchEntry): Seq[Ast] = {
    entry.getLabels.asScala.toList match {
      case Nil =>
        val target = NewJumpTarget()
          .name("default")
          .code("default")
        Seq(Ast(target))

      case labels =>
        labels.flatMap { label =>
          val jumpTarget = NewJumpTarget()
            .name("case")
            .code(label.toString)
          val labelAsts = astsForExpression(label, None).toList

          Ast(jumpTarget) :: labelAsts
        }
    }
  }

  def astForSwitchEntry(entry: SwitchEntry): Seq[Ast] = {
    val labelAsts = astsForSwitchCases(entry)

    val statementAsts = entry.getStatements.asScala.flatMap(astsForStatement)

    labelAsts ++ statementAsts
  }

  private def astForAssertStatement(stmt: AssertStmt): Ast = {
    val callNode = NewCall()
      .name("assert")
      .methodFullName("assert")
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .code(stmt.toString)
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))

    val args = astsForExpression(stmt.getCheck, Some(ExpectedType.Boolean))
    callAst(callNode, args)
  }

  private def astForBlockStatement(
    stmt: BlockStmt,
    codeStr: String = "<empty>",
    prefixAsts: Seq[Ast] = Seq.empty
  ): Ast = {
    scopeStack.pushNewScope(BlockScope)

    val block = NewBlock()
      .code(codeStr)
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))

    val stmtAsts = stmt.getStatements.asScala.flatMap(astsForStatement)

    scopeStack.popScope()
    Ast(block)
      .withChildren(prefixAsts)
      .withChildren(stmtAsts)
  }

  private def astForReturnNode(ret: ReturnStmt): Ast = {
    val returnNode = NewReturn()
      .lineNumber(line(ret))
      .columnNumber(column(ret))
      .code(ret.toString)
    if (ret.getExpression.isPresent) {
      val expectedType = scopeStack.getEnclosingMethodReturnType
      val exprAsts     = astsForExpression(ret.getExpression.get(), expectedType)
      returnAst(returnNode, exprAsts)
    } else {
      Ast(returnNode)
    }
  }

  def astForUnaryExpr(expr: UnaryExpr, expectedType: Option[ExpectedType]): Ast = {
    val operatorName = expr.getOperator match {
      case UnaryExpr.Operator.LOGICAL_COMPLEMENT => Operators.logicalNot
      case UnaryExpr.Operator.POSTFIX_DECREMENT  => Operators.postDecrement
      case UnaryExpr.Operator.POSTFIX_INCREMENT  => Operators.postIncrement
      case UnaryExpr.Operator.PREFIX_DECREMENT   => Operators.preDecrement
      case UnaryExpr.Operator.PREFIX_INCREMENT   => Operators.preIncrement
      case UnaryExpr.Operator.BITWISE_COMPLEMENT => Operators.not
      case UnaryExpr.Operator.PLUS               => Operators.plus
      case UnaryExpr.Operator.MINUS              => Operators.minus
    }

    val argsAsts = astsForExpression(expr.getExpression, expectedType)

    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(argsAsts.headOption.flatMap(rootType))
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val callNode = operatorCallNode(
      operatorName,
      code = expr.toString,
      typeFullName = Some(typeFullName),
      line = line(expr),
      column = column(expr)
    )

    callAst(callNode, argsAsts)
  }

  def astForArrayAccessExpr(expr: ArrayAccessExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)
    val callNode = operatorCallNode(
      Operators.indexAccess,
      code = expr.toString,
      typeFullName = Some(typeFullName),
      line = line(expr),
      column = column(expr)
    )

    val arrayExpectedType = expectedType.map(exp => ExpectedType(exp.fullName ++ "[]", exp.resolvedType))
    val nameAst           = astsForExpression(expr.getName, arrayExpectedType)
    val indexAst          = astsForExpression(expr.getIndex, Some(ExpectedType.Int))
    val args              = nameAst ++ indexAst
    callAst(callNode, args)
  }

  def astForArrayCreationExpr(expr: ArrayCreationExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName = expressionReturnTypeFullName(expr).orElse(expectedType.map(_.fullName))
    val callNode     = operatorCallNode(Operators.alloc, code = expr.toString, typeFullName = typeFullName)

    val levelAsts = expr.getLevels.asScala.flatMap { lvl =>
      lvl.getDimension.toScala match {
        case Some(dimension) => astsForExpression(dimension, Some(ExpectedType.Int))

        case None => Seq.empty
      }
    }

    val initializerAst =
      expr.getInitializer.toScala
        .map(astForArrayInitializerExpr(_, expectedType))

    val args = (levelAsts ++ initializerAst.toList).toSeq

    callAst(callNode, args)
  }

  def astForArrayInitializerExpr(expr: ArrayInitializerExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(expectedType.map(_.fullName))
    val callNode = operatorCallNode(
      Operators.arrayInitializer,
      code = expr.toString,
      typeFullName = typeFullName,
      line = line(expr),
      column = column(expr)
    )

    val MAX_INITIALIZERS = 1000

    val expectedValueType = expr.getValues.asScala.headOption.flatMap { value =>
      // typeName and resolvedType may represent different types since typeName can fall
      // back to known information or primitive types. While this certainly isn't ideal,
      // it shouldn't cause issues since resolvedType is only used where the extra type
      // information not available in typeName is necessary.
      val typeName     = expressionReturnTypeFullName(value)
      val resolvedType = Try(value.calculateResolvedType()).toOption
      typeName.map(ExpectedType(_, resolvedType))
    }
    val args = expr.getValues.asScala
      .slice(0, MAX_INITIALIZERS)
      .flatMap(astsForExpression(_, expectedValueType))
      .toSeq

    val ast = callAst(callNode, args)

    if (expr.getValues.size() > MAX_INITIALIZERS) {
      val placeholder = NewLiteral()
        .typeFullName("ANY")
        .code("<too-many-initializers>")
        .lineNumber(line(expr))
        .columnNumber(column(expr))
      ast.withChild(Ast(placeholder)).withArgEdge(callNode, placeholder)
    } else {
      ast
    }
  }

  def astForBinaryExpr(expr: BinaryExpr, expectedType: Option[ExpectedType]): Ast = {
    val operatorName = expr.getOperator match {
      case BinaryExpr.Operator.OR                   => Operators.logicalOr
      case BinaryExpr.Operator.AND                  => Operators.logicalAnd
      case BinaryExpr.Operator.BINARY_OR            => Operators.or
      case BinaryExpr.Operator.BINARY_AND           => Operators.and
      case BinaryExpr.Operator.DIVIDE               => Operators.division
      case BinaryExpr.Operator.EQUALS               => Operators.equals
      case BinaryExpr.Operator.GREATER              => Operators.greaterThan
      case BinaryExpr.Operator.GREATER_EQUALS       => Operators.greaterEqualsThan
      case BinaryExpr.Operator.LESS                 => Operators.lessThan
      case BinaryExpr.Operator.LESS_EQUALS          => Operators.lessEqualsThan
      case BinaryExpr.Operator.LEFT_SHIFT           => Operators.shiftLeft
      case BinaryExpr.Operator.SIGNED_RIGHT_SHIFT   => Operators.logicalShiftRight
      case BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT => Operators.arithmeticShiftRight
      case BinaryExpr.Operator.XOR                  => Operators.xor
      case BinaryExpr.Operator.NOT_EQUALS           => Operators.notEquals
      case BinaryExpr.Operator.PLUS                 => Operators.addition
      case BinaryExpr.Operator.MINUS                => Operators.subtraction
      case BinaryExpr.Operator.MULTIPLY             => Operators.multiplication
      case BinaryExpr.Operator.REMAINDER            => Operators.modulo
    }

    val args =
      astsForExpression(expr.getLeft, expectedType) ++ astsForExpression(expr.getRight, expectedType)

    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(args.headOption.flatMap(rootType))
        .orElse(args.lastOption.flatMap(rootType))
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val callNode = operatorCallNode(
      operatorName,
      code = expr.toString,
      typeFullName = Some(typeFullName),
      line = line(expr),
      column = column(expr)
    )

    callAst(callNode, args)
  }

  def astForCastExpr(expr: CastExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName =
      typeInfoCalc
        .fullName(expr.getType)
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val callNode = operatorCallNode(
      Operators.cast,
      code = expr.toString,
      typeFullName = Some(typeFullName),
      line = line(expr),
      column = column(expr)
    )

    val typeNode = NewTypeRef()
      .code(expr.getType.toString)
      .typeFullName(typeFullName)
      .lineNumber(line(expr))
      .columnNumber(column(expr))
    val typeAst = Ast(typeNode)

    val exprAst = astsForExpression(expr.getExpression, None)

    callAst(callNode, Seq(typeAst) ++ exprAst)
  }

  def astsForAssignExpr(expr: AssignExpr, expectedExprType: Option[ExpectedType]): Seq[Ast] = {
    val operatorName = expr.getOperator match {
      case Operator.ASSIGN               => Operators.assignment
      case Operator.PLUS                 => Operators.assignmentPlus
      case Operator.MINUS                => Operators.assignmentMinus
      case Operator.MULTIPLY             => Operators.assignmentMultiplication
      case Operator.DIVIDE               => Operators.assignmentDivision
      case Operator.BINARY_AND           => Operators.assignmentAnd
      case Operator.BINARY_OR            => Operators.assignmentOr
      case Operator.XOR                  => Operators.assignmentXor
      case Operator.REMAINDER            => Operators.assignmentModulo
      case Operator.LEFT_SHIFT           => Operators.assignmentShiftLeft
      case Operator.SIGNED_RIGHT_SHIFT   => Operators.assignmentArithmeticShiftRight
      case Operator.UNSIGNED_RIGHT_SHIFT => Operators.assignmentLogicalShiftRight
    }

    val maybeResolvedType = Try(expr.getTarget.calculateResolvedType()).toOption
    val expectedType = maybeResolvedType
      .map { resolvedType =>
        ExpectedType(typeInfoCalc.fullName(resolvedType), Some(resolvedType))
      }
      .orElse(expectedExprType) // resolved target type should be more accurate
    val targetAst = astsForExpression(expr.getTarget, expectedType)
    val argsAsts  = astsForExpression(expr.getValue, expectedType)
    val valueType = argsAsts.headOption.flatMap(rootType)

    val typeFullName =
      targetAst.headOption
        .flatMap(rootType)
        .orElse(valueType)
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val code = s"${rootCode(targetAst)} ${expr.getOperator.asString} ${rootCode(argsAsts)}"

    val callNode = operatorCallNode(operatorName, code, Some(typeFullName), line(expr), column(expr))

    if (partialConstructorQueue.isEmpty) {
      val assignAst = callAst(callNode, targetAst ++ argsAsts)
      Seq(assignAst)
    } else {
      if (partialConstructorQueue.size > 1) {
        logger.warn("BUG: Received multiple partial constructors from assignment. Dropping all but the first.")
      }
      val partialConstructor = partialConstructorQueue.head
      partialConstructorQueue.clear()

      targetAst.flatMap(_.root).toList match {
        case List(identifier: NewIdentifier) =>
          // In this case we have a simple assign. No block needed.
          // e.g. Foo f = new Foo();
          val initAst = completeInitForConstructor(partialConstructor, identifier)
          Seq(callAst(callNode, targetAst ++ argsAsts), initAst)

        case _ =>
          // In this case the left hand side is more complex than an identifier, so
          // we need to contain the constructor in a block.
          // e.g. items[10] = new Foo();
          val valueAst = partialConstructor.blockAst
          Seq(callAst(callNode, targetAst ++ Seq(valueAst)))
      }
    }
  }

  private def localsForVarDecl(varDecl: VariableDeclarationExpr): List[NewLocal] = {
    varDecl.getVariables.asScala.map { variable =>
      val name = variable.getName.toString
      val typeFullName = typeInfoCalc
        .fullName(variable.getType)
        .orElse(scopeStack.lookupVariable(variable.getTypeAsString).map(_.node.typeFullName)) // TODO: TYPE_CLEANUP
        .getOrElse(TypeConstants.UnresolvedType)
      val code = s"${variable.getType} $name"

      NewLocal().name(name).code(code).typeFullName(typeFullName)
    }.toList
  }

  private def assignmentsForVarDecl(
    variables: Iterable[VariableDeclarator],
    lineNumber: Option[Integer],
    columnNumber: Option[Integer]
  ): Seq[Ast] = {
    val variablesWithInitializers =
      variables.filter(_.getInitializer.toScala.isDefined)
    val assignments = variablesWithInitializers.flatMap { variable =>
      val name                    = variable.getName.toString
      val initializer             = variable.getInitializer.toScala.get // Won't crash because of filter
      val initializerTypeFullName = variable.getInitializer.toScala.flatMap(expressionReturnTypeFullName)
      val javaParserVarType       = variable.getTypeAsString
      val variableTypeFullName =
        typeInfoCalc
          .fullName(variable.getType)
          .orElse(scopeStack.lookupVariableType(name))
          .orElse(scopeStack.lookupVariableType(javaParserVarType))
          .orElse(scopeStack.getWildcardType(javaParserVarType))

      val typeFullName =
        variableTypeFullName.orElse(initializerTypeFullName).getOrElse(TypeConstants.UnresolvedType)

      // Need the actual resolvedType here for when the RHS is a lambda expression.
      val resolvedExpectedType = Try(symbolResolver.toResolvedType(variable.getType, classOf[ResolvedType])).toOption
      val initializerAsts      = astsForExpression(initializer, Some(ExpectedType(typeFullName, resolvedExpectedType)))

      val typeName = TypeNodePass.fullToShortName(typeFullName)
      val code     = s"$typeName $name = ${rootCode(initializerAsts)}"

      val callNode = NewCall()
        .name(Operators.assignment)
        .methodFullName(Operators.assignment)
        .code(code)
        .lineNumber(lineNumber)
        .columnNumber(columnNumber)
        .typeFullName(typeFullName)
        .dispatchType(DispatchTypes.STATIC_DISPATCH)

      val identifier = NewIdentifier()
        .name(name)
        .code(name)
        .typeFullName(typeFullName)
        .lineNumber(line(variable))
        .columnNumber(column(variable))
      val localCorrespToIdent = scopeStack.lookupVariable(name).map(_.node)
      val targetAst           = Ast(identifier).withRefEdges(identifier, localCorrespToIdent.toList)

      // Since all partial constructors will be dealt with here, don't pass them up.
      val declAst = callAst(callNode, Seq(targetAst) ++ initializerAsts)

      val constructorAsts = partialConstructorQueue.map(completeInitForConstructor(_, identifier))
      partialConstructorQueue.clear()

      Seq(declAst) ++ constructorAsts
    }

    assignments.toList
  }

  private def completeInitForConstructor(partialConstructor: PartialConstructor, identifier: NewIdentifier): Ast = {
    val initNode = partialConstructor.initNode

    val objectNode = identifier.copy

    val args = partialConstructor.initArgs

    callAst(initNode, args.toList, Some(Ast(objectNode)), withRecvArgEdge = true)
  }

  def astsForVariableDecl(varDecl: VariableDeclarationExpr): Seq[Ast] = {

    val locals    = localsForVarDecl(varDecl)
    val localAsts = locals.map { Ast(_) }

    locals.foreach { local =>
      scopeStack.addToScope(local.name, local)
    }

    val assignments =
      assignmentsForVarDecl(varDecl.getVariables.asScala, line(varDecl), column(varDecl))

    localAsts ++ assignments
  }

  def astForClassExpr(expr: ClassExpr): Ast = {
    val callNode = NewCall()
      .name(Operators.fieldAccess)
      .typeFullName(TypeConstants.Class)
      .methodFullName(Operators.fieldAccess)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .code(expr.toString)

    val identifier = NewIdentifier()
      .typeFullName(typeInfoCalc.fullName(expr.getType).getOrElse(TypeConstants.UnresolvedType))
      .code(expr.getTypeAsString)
      .lineNumber(line(expr))
      .columnNumber(column(expr))
    val idAst = Ast(identifier)

    val fieldIdentifier = NewFieldIdentifier()
      .canonicalName("class")
      .code("class")
      .lineNumber(line(expr))
      .columnNumber(column(expr))
    val fieldIdAst = Ast(fieldIdentifier)

    callAst(callNode, Seq(idAst, fieldIdAst))
  }

  def astForConditionalExpr(expr: ConditionalExpr, expectedType: Option[ExpectedType]): Ast = {
    val condAst = astsForExpression(expr.getCondition, Some(ExpectedType.Boolean))
    val thenAst = astsForExpression(expr.getThenExpr, expectedType)
    val elseAst = astsForExpression(expr.getElseExpr, expectedType)

    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(thenAst.headOption.flatMap(rootType))
        .orElse(elseAst.headOption.flatMap(rootType))
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val callNode = NewCall()
      .name(Operators.conditional)
      .methodFullName(Operators.conditional)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .code(expr.toString)
      .lineNumber(line(expr))
      .columnNumber(column(expr))
      .typeFullName(typeFullName)

    callAst(callNode, condAst ++ thenAst ++ elseAst)
  }

  def astForEnclosedExpression(expr: EnclosedExpr, expectedType: Option[ExpectedType]): Seq[Ast] = {
    astsForExpression(expr.getInner, expectedType)
  }

  def astForFieldAccessExpr(expr: FieldAccessExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val callNode = NewCall()
      .name(Operators.fieldAccess)
      .methodFullName(Operators.fieldAccess)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .code(expr.toString)
      .lineNumber(line(expr))
      .columnNumber(column(expr))
      .typeFullName(typeFullName)

    val fieldIdentifier = expr.getName
    val identifierAsts  = astsForExpression(expr.getScope, None)
    val fieldIdentifierNode = NewFieldIdentifier()
      .canonicalName(fieldIdentifier.toString)
      .lineNumber(line(fieldIdentifier))
      .columnNumber(column(fieldIdentifier))
      .code(fieldIdentifier.toString)
    val fieldIdAst = Ast(fieldIdentifierNode)

    callAst(callNode, identifierAsts ++ Seq(fieldIdAst))
  }

  def astForInstanceOfExpr(expr: InstanceOfExpr): Ast = {
    val callNode = NewCall()
      .name(Operators.instanceOf)
      .methodFullName(Operators.instanceOf)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .code(expr.toString)
      .lineNumber(line(expr))
      .columnNumber(column(expr))
      .typeFullName(TypeConstants.Boolean)

    val exprAst      = astsForExpression(expr.getExpression, None)
    val typeFullName = typeInfoCalc.fullName(expr.getType).getOrElse(TypeConstants.UnresolvedType)
    val typeNode =
      NewTypeRef()
        .code(expr.getType.toString)
        .lineNumber(line(expr))
        .columnNumber(column(expr.getType))
        .typeFullName(typeFullName)
    val typeAst = Ast(typeNode)

    callAst(callNode, exprAst ++ Seq(typeAst))
  }

  def astForNameExpr(x: NameExpr, expectedType: Option[ExpectedType]): Ast = {
    val name = x.getName.toString
    val typeFullName = expressionReturnTypeFullName(x)
      .orElse(expectedType.map(_.fullName))
      .getOrElse(TypeConstants.UnresolvedType)

    Try(x.resolve()) match {
      case Success(value) if value.isField =>
        val identifierName = if (value.asField.isStatic) {
          // A static field represented by a NameExpr must belong to the class in which it's used. Static fields
          // from other classes are represented by a FieldAccessExpr instead.
          scopeStack.getEnclosingTypeDecl.map(_.name).getOrElse(TypeConstants.UnresolvedType)
        } else {
          "this"
        }

        val identifierTypeFullName =
          value match {
            case fieldDecl: ResolvedFieldDeclaration =>
              // TODO It is not quite correct to use the declaring classes type.
              // Instead we should take the using classes type which is either the same or a
              // sub class of the declaring class.
              typeInfoCalc.fullName(fieldDecl.declaringType())
            case anotherFieldDecl: ResolvedFieldDeclaration =>
              // This is created in JavaParserClassDeclaration.
              typeInfoCalc.fullName(anotherFieldDecl)
          }

        val identifier = NewIdentifier()
          .name(identifierName)
          .typeFullName(identifierTypeFullName)
          .lineNumber(line(x))
          .columnNumber(column(x))
          .code(identifierName)

        val fieldIdentifier = NewFieldIdentifier()
          .code(x.toString)
          .canonicalName(name)
          .lineNumber(line(x))
          .columnNumber(column(x))

        val fieldAccess = NewCall()
          .name(Operators.fieldAccess)
          .methodFullName(Operators.fieldAccess)
          .dispatchType(DispatchTypes.STATIC_DISPATCH)
          .code(name)
          .typeFullName(typeFullName)
          .lineNumber(line(x))
          .columnNumber(column(x))

        val identifierAst = Ast(identifier)
        val fieldIdentAst = Ast(fieldIdentifier)

        callAst(fieldAccess, Seq(identifierAst, fieldIdentAst))

      case _ =>
        val identifier = NewIdentifier()
          .name(name)
          .code(name)
          .typeFullName(typeFullName)
          .lineNumber(line(x.getName))
          .columnNumber(column(x.getName))

        val variableOption = scopeStack
          .lookupVariable(name)
          .filter(variableInfo =>
            variableInfo.node.isInstanceOf[NewMethodParameterIn] || variableInfo.node.isInstanceOf[NewLocal]
          )

        variableOption.foldLeft(Ast(identifier))((ast, variableInfo) => ast.withRefEdge(identifier, variableInfo.node))
    }

  }

  /** The below representation for constructor invocations and object creations was chosen for the sake of consistency
    * with the Java frontend. It follows the bytecode approach of splitting a constructor call into separate `alloc` and
    * `init` calls.
    *
    * There are two cases to consider. The first is a constructor invocation in an assignment, for example:
    *
    * Foo f = new Foo(42);
    *
    * is represented as
    *
    * Foo f = <operator>.alloc() f.init(42);
    *
    * The second case is a constructor invocation not in an assignment, for example as an argument to a method call. In
    * this case, the representation does not stay as close to Java as in case
    *   1. In particular, a new BLOCK is introduced to contain the constructor invocation. For example:
    *
    * foo(new Foo(42));
    *
    * is represented as
    *
    * foo({ Foo temp = alloc(); temp.init(42); temp })
    *
    * This is not valid Java code, but this representation is a decent compromise between staying faithful to Java and
    * being consistent with the Java bytecode frontend.
    */
  def astForObjectCreationExpr(expr: ObjectCreationExpr, expectedType: Option[ExpectedType]): Ast = {
    val maybeResolvedExpr = Try(expr.resolve())
    val argumentAsts      = argAstsForCall(expr, maybeResolvedExpr, expr.getArguments)

    val allocFullName = Operators.alloc
    val typeFullName = typeInfoCalc
      .fullName(expr.getType)
      .orElse(expectedType.map(_.fullName))
      .getOrElse(TypeConstants.UnresolvedType)

    val signature =
      maybeResolvedExpr match {
        case Success(constructor) =>
          constructorSignature(constructor, ResolvedTypeParametersMap.empty())
        case _ =>
          // Fallback. Method could not be resolved. So we fall back to using
          // expressionTypeFullName and the argument types to approximate the method
          // signature.
          val argumentTypes = argumentAsts.map(arg => rootType(arg).getOrElse(TypeConstants.UnresolvedType))
          composeMethodLikeSignature(TypeConstants.Void, argumentTypes)
      }

    val initFullName = composeMethodFullName(typeFullName, "<init>", signature)

    val allocNode = NewCall()
      .name(allocFullName)
      .methodFullName(allocFullName)
      .code(expr.toString)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(typeFullName)
      .lineNumber(line(expr))
      .columnNumber(column(expr))
      .signature(s"$typeFullName()")

    val initNode = NewCall()
      .name("<init>")
      .methodFullName(initFullName)
      .lineNumber(line(expr))
      .typeFullName(TypeConstants.Void)
      .code(expr.toString)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .signature(signature)

    // Assume that a block ast is required, since there isn't enough information to decide otherwise.
    // This simplifies logic elsewhere, and unnecessary blocks will be garbage collected soon.
    val blockAst = blockAstForConstructorInvocation(line(expr), column(expr), allocNode, initNode, argumentAsts)

    expr.getParentNode.toScala match {
      case Some(parent) if parent.isInstanceOf[VariableDeclarator] || parent.isInstanceOf[AssignExpr] =>
        val partialConstructor = PartialConstructor(initNode, argumentAsts, blockAst)
        partialConstructorQueue.append(partialConstructor)
        Ast(allocNode)

      case _ =>
        blockAst
    }
  }

  private var tempConstCount = 0
  private def blockAstForConstructorInvocation(
    lineNumber: Option[Integer],
    columnNumber: Option[Integer],
    allocNode: NewCall,
    initNode: NewCall,
    args: Seq[Ast]
  ): Ast = {
    val blockNode = NewBlock()
      .lineNumber(lineNumber)
      .columnNumber(columnNumber)
      .typeFullName(allocNode.typeFullName)

    val tempName = "$obj" ++ tempConstCount.toString
    tempConstCount += 1
    val identifier = NewIdentifier()
      .name(tempName)
      .code(tempName)
      .typeFullName(allocNode.typeFullName)
    val identifierAst = Ast(identifier)

    val allocAst = Ast(allocNode)

    val assignmentNode = NewCall()
      .name(Operators.assignment)
      .methodFullName(Operators.assignment)
      .typeFullName(allocNode.typeFullName)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)

    val assignmentAst = callAst(assignmentNode, List(identifierAst, allocAst))

    val identifierWithDefaultOrder = identifier.copy.order(PropertyDefaults.Order)
    val identifierForInit          = identifierWithDefaultOrder.copy
    val initWithDefaultOrder       = initNode.order(PropertyDefaults.Order)
    val initAst = callAst(initWithDefaultOrder, args, Some(Ast(identifierForInit)), withRecvArgEdge = true)

    val returnAst = Ast(identifierWithDefaultOrder.copy)

    Ast(blockNode)
      .withChild(assignmentAst)
      .withChild(initAst)
      .withChild(returnAst)
  }

  def astForThisExpr(expr: ThisExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName =
      expressionReturnTypeFullName(expr)
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val identifier =
      NewIdentifier()
        .name("this")
        .typeFullName(typeFullName)
        .code(expr.toString)
        .lineNumber(line(expr))
        .columnNumber(column(expr))

    Ast(identifier)
  }

  private def astForExplicitConstructorInvocation(stmt: ExplicitConstructorInvocationStmt): Ast = {
    val maybeResolved = Try(stmt.resolve())
    val args          = argAstsForCall(stmt, maybeResolved, stmt.getArguments)

    val typeFullName = Try(stmt.resolve())
      .map(_.declaringType())
      .map(typeInfoCalc.fullName)
      .getOrElse(TypeConstants.UnresolvedType)
    val argTypes = argumentTypesForCall(Try(stmt.resolve()), args)

    val signature = s"${TypeConstants.Void}(${argTypes.mkString(",")})"
    val callNode = NewCall()
      .name("<init>")
      .methodFullName(composeMethodFullName(typeFullName, "<init>", signature))
      .code(stmt.toString)
      .lineNumber(line(stmt))
      .columnNumber(column(stmt))
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .signature(signature)
      .typeFullName(TypeConstants.Void)

    val thisNode = NewIdentifier()
      .name("this")
      .code("this")
      .typeFullName(typeFullName)
    val thisAst = Ast(thisNode)

    callAst(callNode, args, Some(thisAst), withRecvArgEdge = true)
  }

  private def astsForExpression(expression: Expression, expectedType: Option[ExpectedType]): Seq[Ast] = {
    // TODO: Implement missing handlers
    // case _: MethodReferenceExpr     => Seq()
    // case _: PatternExpr             => Seq()
    // case _: SuperExpr               => Seq()
    // case _: SwitchExpr              => Seq()
    // case _: TypeExpr                => Seq()
    expression match {
      case _: AnnotationExpr          => Seq()
      case x: ArrayAccessExpr         => Seq(astForArrayAccessExpr(x, expectedType))
      case x: ArrayCreationExpr       => Seq(astForArrayCreationExpr(x, expectedType))
      case x: ArrayInitializerExpr    => Seq(astForArrayInitializerExpr(x, expectedType))
      case x: AssignExpr              => astsForAssignExpr(x, expectedType)
      case x: BinaryExpr              => Seq(astForBinaryExpr(x, expectedType))
      case x: CastExpr                => Seq(astForCastExpr(x, expectedType))
      case x: ClassExpr               => Seq(astForClassExpr(x))
      case x: ConditionalExpr         => Seq(astForConditionalExpr(x, expectedType))
      case x: EnclosedExpr            => astForEnclosedExpression(x, expectedType)
      case x: FieldAccessExpr         => Seq(astForFieldAccessExpr(x, expectedType))
      case x: InstanceOfExpr          => Seq(astForInstanceOfExpr(x))
      case x: LambdaExpr              => Seq(astForLambdaExpr(x, expectedType))
      case x: LiteralExpr             => Seq(astForLiteralExpr(x))
      case x: MethodCallExpr          => Seq(astForMethodCall(x, expectedType))
      case x: NameExpr                => Seq(astForNameExpr(x, expectedType))
      case x: ObjectCreationExpr      => Seq(astForObjectCreationExpr(x, expectedType))
      case x: SuperExpr               => Seq(astForSuperExpr(x, expectedType))
      case x: ThisExpr                => Seq(astForThisExpr(x, expectedType))
      case x: UnaryExpr               => Seq(astForUnaryExpr(x, expectedType))
      case x: VariableDeclarationExpr => astsForVariableDecl(x)
      case x                          => Seq(unknownAst(x))
    }
  }

  private def unknownAst(node: Node): Ast = {
    val unknownNode =
      NewUnknown()
        .code(node.toString)
        .lineNumber(line(node))
        .columnNumber(column(node))

    Ast(unknownNode)
  }

  private def codeForScopeExpr(scopeExpr: Expression, isScopeForStaticCall: Boolean): Option[String] = {
    scopeExpr match {
      case scope: NameExpr => Some(s"${scope.getNameAsString}.")

      case fieldAccess: FieldAccessExpr =>
        val maybeScopeString = codeForScopeExpr(fieldAccess.getScope, isScopeForStaticCall = false)
        val name             = fieldAccess.getNameAsString
        maybeScopeString
          .map { scopeString =>
            s"$scopeString$name"
          }
          .orElse(Some(name))
          .map(result => s"$result.")

      case _: SuperExpr => Some("super.")

      case _: ThisExpr => Some("this.")

      case scopeMethodCall: MethodCallExpr =>
        codePrefixForMethodCall(scopeMethodCall) match {
          case "" => Some("")
          case prefix =>
            val argumentsCode = getArgumentCodeString(scopeMethodCall.getArguments)
            Some(s"$prefix${scopeMethodCall.getNameAsString}($argumentsCode).")
        }

      case objectCreationExpr: ObjectCreationExpr =>
        val typeName        = objectCreationExpr.getTypeAsString
        val argumentsString = getArgumentCodeString(objectCreationExpr.getArguments)
        Some(s"new $typeName($argumentsString).")

      case _ => None
    }
  }

  private def codePrefixForMethodCall(call: MethodCallExpr): String = {
    Try(call.resolve()) match {
      case Success(resolvedCall) =>
        call.getScope.toScala
          .flatMap(codeForScopeExpr(_, resolvedCall.isStatic))
          .getOrElse(if (resolvedCall.isStatic) "" else "this.")

      case _ =>
        // If the call is unresolvable, we cannot make a good guess about what the prefix should be
        ""
    }
  }

  private def createObjectNode(typeFullName: String, call: MethodCallExpr, callNode: NewCall): Option[NewIdentifier] = {
    val maybeScope = call.getScope.toScala

    if (maybeScope.isDefined || callNode.dispatchType == DispatchTypes.DYNAMIC_DISPATCH) {
      val name = maybeScope.map(_.toString).getOrElse("this")
      Some(
        NewIdentifier()
          .name(name)
          .code(name)
          .typeFullName(typeFullName)
          .lineNumber(callNode.lineNumber)
          .columnNumber(callNode.columnNumber)
      )
    } else {
      None
    }
  }

  private def nextLambdaName(): String = {
    s"$LambdaNamePrefix${lambdaKeyPool.next}"
  }

  private def nextIndexName(): String = {
    s"$IndexNamePrefix${indexKeyPool.next}"
  }

  private def nextIterableName(): String = {
    s"$IterableNamePrefix${iterableKeyPool.next}"
  }

  private def genericParamTypeMapForLambda(expectedType: Option[ExpectedType]): ResolvedTypeParametersMap = {
    expectedType
      .flatMap(_.resolvedType)
      // This should always be true for correct code
      .collect { case r: ResolvedReferenceType => r }
      .map(_.typeParametersMap())
      .getOrElse(new ResolvedTypeParametersMap.Builder().build())
  }

  private def buildParamListForLambda(
    expr: LambdaExpr,
    maybeBoundMethod: Option[ResolvedMethodDeclaration],
    expectedTypeParamTypes: ResolvedTypeParametersMap
  ): Seq[Ast] = {
    val lambdaParameters = expr.getParameters.asScala.toList
    // Workaround to handle potential failures in x.getType for resolvedParameters.
    val resolvedParameters = maybeBoundMethod match {
      case Some(resolvedMethod) =>
        (0 until resolvedMethod.getNumberOfParams).map(resolvedMethod.getParam)
      case None => List()
    }
    val resolvedParamsAllHaveTypes = resolvedParameters.foldLeft(true)((b, r) => b && Try(r.getType).toOption.isDefined)
    val paramsWithSubstitutedTypes =
      if (maybeBoundMethod.isDefined && resolvedParamsAllHaveTypes) {
        resolvedParameters.map(_.getType).map {
          case resolvedType: ResolvedTypeVariable =>
            expectedTypeParamTypes.getValue(resolvedType.asTypeParameter)

          case resolvedType => resolvedType
        }
      } else {
        List()
      }
    val paramTypesList =
      if (maybeBoundMethod.isDefined && resolvedParamsAllHaveTypes && !paramsWithSubstitutedTypes.isEmpty) {
        // Substitute generic typeParam with the expected type if it can be found; leave unchanged otherwise.
        paramsWithSubstitutedTypes.map(typeInfoCalc.fullName)
      } else {
        // Unless types are explicitly specified in the lambda definition,
        // this will yield the erased types which is why the actual lambda
        // expression parameters are only used as a fallback.
        lambdaParameters
          .map(_.getType)
          .map(typeInfoCalc.fullName)
          .map(_.getOrElse(TypeConstants.UnresolvedType))
      }

    if (paramTypesList.size != lambdaParameters.size) {
      logger.error(s"Found different number lambda params and param types for $expr. Some parameters will be missing.")
    }

    val parameterNodes = lambdaParameters
      .zip(paramTypesList)
      .zipWithIndex
      .map { case ((param, typ), idx) =>
        val name = param.getNameAsString
        NewMethodParameterIn()
          .name(name)
          .typeFullName(typ)
          .index(idx + 1)
          .order(idx + 1)
          .code(s"$typ $name")
          .evaluationStrategy(EvaluationStrategies.BY_SHARING)
          .lineNumber(line(expr))
      }

    parameterNodes.foreach { paramNode =>
      scopeStack.addToScope(paramNode.name, paramNode)
    }

    parameterNodes.map(Ast(_))
  }

  private def getLambdaReturnType(
    maybeResolvedLambdaType: Option[ResolvedType],
    maybeBoundMethod: Option[ResolvedMethodDeclaration],
    expectedTypeParamTypes: ResolvedTypeParametersMap
  ): String = {
    val maybeBoundMethodReturnType = maybeBoundMethod.map { boundMethod =>
      val retTypeOpt = Try(boundMethod.getReturnType).toOption
      if (!retTypeOpt.isDefined) {
        maybeResolvedLambdaType.get
      } else {
        retTypeOpt.get match {
          case returnType: ResolvedTypeVariable =>
            expectedTypeParamTypes.getValue(returnType.asTypeParameter)

          case returnType => returnType
        }
      }
    }

    val returnType = maybeBoundMethodReturnType.orElse(maybeResolvedLambdaType)

    returnType.map(typeInfoCalc.fullName).getOrElse(TypeConstants.UnresolvedType)
  }

  def closureBinding(closureBindingId: String, originalName: String): NewClosureBinding = {
    NewClosureBinding()
      .closureBindingId(closureBindingId)
      .closureOriginalName(originalName)
      .evaluationStrategy(EvaluationStrategies.BY_SHARING)
  }

  private def closureBindingsForCapturedNodes(
    captured: List[NamedVariableNodeType],
    lambdaMethodName: String
  ): List[ClosureBindingEntry] = {
    captured
      .map { capturedNode =>
        val closureBindingId   = s"$filename:$lambdaMethodName:${capturedNode.name}"
        val closureBindingNode = closureBinding(closureBindingId, capturedNode.name)

        ClosureBindingEntry(capturedNode, closureBindingNode)
      }
  }

  private def localsForCapturedNodes(closureBindingEntries: List[ClosureBindingEntry]): List[NewLocal] = {
    val localsForCaptured =
      closureBindingEntries.map { case ClosureBindingEntry(node, binding) =>
        val local = NewLocal()
          .name(node.name)
          .code(node.name)
          .typeFullName(node.typeFullName)
          .closureBindingId(binding.closureBindingId)
        local
      }
    localsForCaptured.foreach { local => scopeStack.addToScope(local.name, local) }
    localsForCaptured
  }

  private def astForLambdaBody(body: Statement, localsForCapturedVars: Seq[NewLocal], returnType: String): Ast = {
    body match {
      case block: BlockStmt => astForBlockStatement(block, prefixAsts = localsForCapturedVars.map(Ast(_)))

      case stmt =>
        val blockAst = Ast(NewBlock().lineNumber(line(body)))
        val bodyAst = if (returnType == TypeConstants.Void) {
          astsForStatement(stmt)
        } else {
          val returnNode =
            NewReturn()
              .code(s"return ${body.toString}")
              .lineNumber(line(body))
          val returnArgs = astsForStatement(stmt)
          Seq(returnAst(returnNode, returnArgs))
        }

        blockAst
          .withChildren(localsForCapturedVars.map(Ast(_)))
          .withChildren(bodyAst)
    }
  }

  private def createLambdaMethodNode(lambdaName: String, parameters: Seq[Ast], returnType: String): NewMethod = {
    val enclosingTypeName =
      scopeStack.getEnclosingTypeDecl.map(_.fullName).getOrElse(TypeConstants.UnresolvedType)
    val signature =
      s"$returnType(${parameters.map(rootType).map(_.getOrElse(TypeConstants.UnresolvedType)).mkString(",")})"
    val lambdaFullName = composeMethodFullName(enclosingTypeName, lambdaName, signature)

    NewMethod()
      .name(lambdaName)
      .fullName(lambdaFullName)
      .signature(signature)
      .filename(filename)
      .code("<lambda>")
  }

  private def addClosureBindingsToDiffGraph(
    bindingEntries: Iterable[ClosureBindingEntry],
    methodRef: NewMethodRef
  ): Unit = {
    bindingEntries.foreach { case ClosureBindingEntry(node, closureBinding) =>
      diffGraph.addNode(closureBinding)
      diffGraph.addEdge(closureBinding, node, EdgeTypes.REF)
      diffGraph.addEdge(methodRef, closureBinding, EdgeTypes.CAPTURE)
    }
  }

  private def createAndPushLambdaMethod(
    expr: LambdaExpr,
    lambdaMethodName: String,
    implementedInfo: LambdaImplementedInfo,
    localsForCaptured: Seq[NewLocal],
    expectedLambdaType: Option[ExpectedType]
  ): NewMethod = {
    val implementedMethod    = implementedInfo.implementedMethod
    val implementedInterface = implementedInfo.implementedInterface

    // We need to get this information from the expected type as the JavaParser
    // symbol solver returns the erased types when resolving the lambda itself.
    val expectedTypeParamTypes = genericParamTypeMapForLambda(expectedLambdaType)
    val parametersWithoutThis  = buildParamListForLambda(expr, implementedMethod, expectedTypeParamTypes)

    val returnType = getLambdaReturnType(implementedInterface, implementedMethod, expectedTypeParamTypes)

    val lambdaMethodBody = astForLambdaBody(expr.getBody, localsForCaptured, returnType)

    val thisParam = lambdaMethodBody.nodes
      .collect { case identifier: NewIdentifier => identifier }
      .find { identifier => identifier.name == "this" || identifier.name == "super" }
      .map { _ =>
        val typeFullName = scopeStack.getEnclosingTypeDecl.map(_.fullName).getOrElse(TypeConstants.UnresolvedType)
        thisAstForMethod(typeFullName, line(expr))
      }
      .toList

    val parameters = thisParam ++ parametersWithoutThis

    val lambdaMethodNode = createLambdaMethodNode(lambdaMethodName, parametersWithoutThis, returnType)
    val returnNode       = methodReturnNode(returnType, None, line(expr), column(expr))
    val virtualModifier  = Some(NewModifier().modifierType(ModifierTypes.VIRTUAL))
    val staticModifier   = Option.when(thisParam.isEmpty)(NewModifier().modifierType(ModifierTypes.STATIC))
    val privateModifier  = Some(NewModifier().modifierType(ModifierTypes.PRIVATE))

    val modifiers = List(virtualModifier, staticModifier, privateModifier).flatten.map(Ast(_))

    val lambdaParameterNamesToNodes =
      parameters
        .flatMap(_.root)
        .collect { case param: NewMethodParameterIn => param }
        .map { param => param.name -> param }
        .toMap

    val identifiersMatchingParams = lambdaMethodBody.nodes
      .collect { case identifier: NewIdentifier => identifier }
      .filter { identifier => lambdaParameterNamesToNodes.contains(identifier.name) }

    val lambdaMethodAstWithoutRefs =
      Ast(lambdaMethodNode)
        .withChildren(parameters)
        .withChild(lambdaMethodBody)
        .withChild(Ast(returnNode))
        .withChildren(modifiers)

    val lambdaMethodAst = identifiersMatchingParams.foldLeft(lambdaMethodAstWithoutRefs)((ast, identifier) =>
      ast.withRefEdge(identifier, lambdaParameterNamesToNodes(identifier.name))
    )

    scopeStack.addLambdaMethod(lambdaMethodAst)

    lambdaMethodNode
  }

  private def createAndPushLambdaTypeDecl(
    lambdaMethodNode: NewMethod,
    implementedInfo: LambdaImplementedInfo
  ): NewTypeDecl = {
    val inheritsFromTypeFullName =
      implementedInfo.implementedInterface
        .map(typeInfoCalc.fullName)
        .orElse(Some(TypeConstants.Object))
        .toList

    typeInfoCalc.registerType(lambdaMethodNode.fullName)
    val lambdaTypeDeclNode =
      NewTypeDecl()
        .fullName(lambdaMethodNode.fullName)
        .name(lambdaMethodNode.name)
        .inheritsFromTypeFullName(inheritsFromTypeFullName)
    scopeStack.addLambdaDecl(Ast(lambdaTypeDeclNode))

    lambdaTypeDeclNode
  }

  private def getLambdaImplementedInfo(expr: LambdaExpr, expectedType: Option[ExpectedType]): LambdaImplementedInfo = {
    val maybeImplementedType = {
      val maybeResolved = Try(expr.calculateResolvedType())
      maybeResolved.toOption
        .orElse(expectedType.flatMap(_.resolvedType))
        .collect { case refType: ResolvedReferenceType => refType }
    }

    val maybeImplementedInterface = maybeImplementedType.flatMap(_.getTypeDeclaration.toScala)

    if (maybeImplementedInterface.isEmpty) {
      logger.warn(s"Could not resolve the interface implemented by the lambda $expr. Type info may be missing.")
    }

    // By definition, a functional interface will declare exactly one abstract method, so `find` is fine.
    val maybeBoundMethod = maybeImplementedInterface.flatMap(_.getDeclaredMethods.asScala.find(_.isAbstract))

    LambdaImplementedInfo(maybeImplementedType, maybeBoundMethod)
  }

  private def astForLambdaExpr(expr: LambdaExpr, expectedType: Option[ExpectedType]): Ast = {
    scopeStack.pushNewScope(MethodScope(expectedType.getOrElse(ExpectedType.default)))

    val lambdaMethodName = nextLambdaName()

    val capturedVariables              = scopeStack.getCapturedVariables
    val closureBindingsForCapturedVars = closureBindingsForCapturedNodes(capturedVariables, lambdaMethodName)
    val localsForCaptured              = localsForCapturedNodes(closureBindingsForCapturedVars)
    val implementedInfo                = getLambdaImplementedInfo(expr, expectedType)
    val lambdaMethodNode =
      createAndPushLambdaMethod(expr, lambdaMethodName, implementedInfo, localsForCaptured, expectedType)

    val methodRef =
      NewMethodRef()
        .methodFullName(lambdaMethodNode.fullName)
        .typeFullName(lambdaMethodNode.fullName)
        .code(lambdaMethodNode.fullName)

    addClosureBindingsToDiffGraph(closureBindingsForCapturedVars, methodRef)

    val interfaceBinding = implementedInfo.implementedMethod.map { implementedMethod =>
      NewBinding()
        .name(implementedMethod.getName)
        .methodFullName(lambdaMethodNode.fullName)
        .signature(lambdaMethodNode.signature)
    }

    val bindingTable = getLambdaBindingTable(
      LambdaBindingInfo(lambdaMethodNode.fullName, implementedInfo.implementedInterface, interfaceBinding)
    )

    val lambdaTypeDeclNode = createAndPushLambdaTypeDecl(lambdaMethodNode, implementedInfo)
    createBindingNodes(lambdaTypeDeclNode, bindingTable)

    scopeStack.popScope()
    Ast(methodRef)
  }

  private def astForLiteralExpr(expr: LiteralExpr): Ast = {
    Ast(
      NewLiteral()
        .code(expr.toString)
        .typeFullName(expressionReturnTypeFullName(expr).getOrElse(TypeConstants.UnresolvedType))
        .lineNumber(line(expr))
        .columnNumber(column(expr))
    )
  }

  private def getExpectedParamType(
    maybeResolvedCall: Try[ResolvedMethodLikeDeclaration],
    idx: Int
  ): Option[ExpectedType] = {
    maybeResolvedCall.toOption.map { methodDecl =>
      val paramCount = methodDecl.getNumberOfParams

      val resolvedType = if (idx < paramCount) {
        Some(methodDecl.getParam(idx).getType)
      } else if (paramCount > 0 && methodDecl.getParam(paramCount - 1).isVariadic) {
        Some(methodDecl.getParam(paramCount - 1).getType)
      } else {
        None
      }

      val typeName = resolvedType.map(typeInfoCalc.fullName)
      ExpectedType(typeName.getOrElse(TypeConstants.UnresolvedType), resolvedType)
    }
  }

  private def dispatchTypeForCall(maybeDecl: Try[ResolvedMethodDeclaration], maybeScope: Option[Expression]): String = {
    maybeScope match {
      case Some(_: SuperExpr) =>
        DispatchTypes.STATIC_DISPATCH
      case _ =>
        maybeDecl match {
          case Success(decl) =>
            if (decl.isStatic) DispatchTypes.STATIC_DISPATCH else DispatchTypes.DYNAMIC_DISPATCH

          case _ =>
            DispatchTypes.DYNAMIC_DISPATCH
        }
    }
  }

  private def targetTypeForCall(callExpr: MethodCallExpr): Option[String] = {
    callExpr.getScope.toScala match {
      case Some(scope: ThisExpr) =>
        expressionReturnTypeFullName(scope)
          .orElse(scopeStack.getEnclosingTypeDecl.map(_.fullName))

      case Some(scope: SuperExpr) =>
        expressionReturnTypeFullName(scope)
          .orElse(scopeStack.getEnclosingTypeDecl.flatMap(_.inheritsFromTypeFullName.headOption))

      case Some(scope) => expressionReturnTypeFullName(scope)

      case None =>
        Try(callExpr.resolve()).toOption
          .flatMap { methodDeclOption =>
            if (methodDeclOption.isStatic) Some(typeInfoCalc.fullName(methodDeclOption.declaringType()))
            else scopeStack.getEnclosingTypeDecl.map(_.fullName)
          }
          .orElse(scopeStack.getEnclosingTypeDecl.map(_.fullName))
    }
  }

  private def argumentTypesForCall(maybeMethod: Try[ResolvedMethodLikeDeclaration], argAsts: Seq[Ast]): List[String] = {
    maybeMethod match {
      case Success(resolved) =>
        (0 until resolved.getNumberOfParams).map { idx =>
          val param = resolved.getParam(idx)
          typeInfoCalc.fullName(param.getType)
        }.toList

      case Failure(_) =>
        // Fall back to actual argument types if the called method couldn't be resolved.
        // This may result in missing dataflows.
        argAsts.map(arg => rootType(arg).getOrElse(TypeConstants.UnresolvedType)).toList
    }
  }

  private def argAstsForCall(
    call: Node,
    tryResolvedDecl: Try[ResolvedMethodLikeDeclaration],
    args: NodeList[Expression]
  ): Seq[Ast] = {
    val hasVariadicParameter = tryResolvedDecl.map(_.hasVariadicParameter).getOrElse(false)
    val paramCount           = tryResolvedDecl.map(_.getNumberOfParams).getOrElse(-1)

    val argsAsts = args.asScala.zipWithIndex.flatMap { case (arg, idx) =>
      val expectedType = getExpectedParamType(tryResolvedDecl, idx)
      astsForExpression(arg, expectedType)
    }.toList

    tryResolvedDecl match {
      case Success(_) if hasVariadicParameter =>
        val expectedVariadicTypeFullName =
          getExpectedParamType(tryResolvedDecl, paramCount - 1)
            .map(_.fullName)
            .getOrElse(TypeConstants.UnresolvedType)
        val (regularArgs, varargs) = argsAsts.splitAt(paramCount - 1)
        val arrayInitializer =
          NewCall()
            .name(Operators.arrayInitializer)
            .methodFullName(Operators.arrayInitializer)
            .code(Operators.arrayInitializer)
            .typeFullName(expectedVariadicTypeFullName)
            .dispatchType(DispatchTypes.STATIC_DISPATCH)
            .lineNumber(line(call))
            .columnNumber(column(call))

        val arrayInitializerAst = callAst(arrayInitializer, varargs)

        regularArgs ++ Seq(arrayInitializerAst)

      case _ => argsAsts
    }
  }

  private def getArgumentCodeString(args: NodeList[Expression]): String = {
    args.asScala
      .map {
        case _: LambdaExpr => "<lambda>"
        case other         => other.toString
      }
      .mkString(", ")
  }

  private def astForMethodCall(call: MethodCallExpr, expectedReturnType: Option[ExpectedType]): Ast = {
    val maybeResolvedCall = Try(call.resolve())
    val argumentAsts      = argAstsForCall(call, maybeResolvedCall, call.getArguments)

    val expressionTypeFullName = expressionReturnTypeFullName(call)
      .orElse(expectedReturnType.map(_.fullName))
      .getOrElse(TypeConstants.UnresolvedType)

    val signature =
      maybeResolvedCall match {
        case Success(method) =>
          methodSignature(method, ResolvedTypeParametersMap.empty())
        case _ =>
          // Fallback. Method could not be resolved. So we fall back to using
          // expressionTypeFullName and the argument types to approximate the method
          // signature.
          val argumentTypes = argumentAsts.map(arg => rootType(arg).getOrElse(TypeConstants.UnresolvedType))
          composeMethodLikeSignature(expressionTypeFullName, argumentTypes)
      }

    val receiverTypeOption = targetTypeForCall(call)
    val receiverType       = receiverTypeOption.getOrElse(TypeConstants.UnresolvedReceiver)

    val methodFullName = composeMethodFullName(receiverType, call.getNameAsString, signature)

    val dispatchType = dispatchTypeForCall(maybeResolvedCall, call.getScope.toScala)

    val argumentsCode = getArgumentCodeString(call.getArguments)

    val codePrefix = codePrefixForMethodCall(call)
    val callNode = NewCall()
      .typeFullName(expressionTypeFullName)
      .name(call.getNameAsString)
      .methodFullName(methodFullName)
      .signature(signature)
      .dispatchType(dispatchType)
      .code(s"$codePrefix${call.getNameAsString}($argumentsCode)")
      .lineNumber(line(call))
      .columnNumber(column(call))

    val scopeAsts = call.getScope.toScala match {
      case Some(scope) =>
        astsForExpression(scope, receiverTypeOption.map(ExpectedType(_)))

      case None =>
        val objectNode =
          createObjectNode(receiverTypeOption.getOrElse(TypeConstants.UnresolvedType), call, callNode)
        objectNode.map(Ast(_)).toList
    }

    callAst(callNode, argumentAsts, scopeAsts.headOption, withRecvArgEdge = true)
  }

  def astForSuperExpr(superExpr: SuperExpr, expectedType: Option[ExpectedType]): Ast = {
    val typeFullName =
      expressionReturnTypeFullName(superExpr)
        .orElse(expectedType.map(_.fullName))
        .getOrElse(TypeConstants.UnresolvedType)

    val thisIdentifier = NewIdentifier()
      .name("this")
      .code("super")
      .typeFullName(typeFullName)
      .lineNumber(line(superExpr))
      .columnNumber(column(superExpr))

    Ast(thisIdentifier)
  }

  private def astsForParameterList(parameters: NodeList[Parameter]): Seq[Ast] = {
    parameters.asScala.toList.zipWithIndex.map { case (param, idx) =>
      astForParameter(param, idx + 1)
    }
  }

  private def astForParameter(parameter: Parameter, childNum: Int): Ast = {
    val maybeArraySuffix = if (parameter.isVarArgs) "[]" else ""
    val typeFullName = {
      typeInfoCalc
        .fullName(parameter.getType)
        .orElse(scopeStack.lookupVariableType(parameter.getTypeAsString))
        .orElse(scopeStack.getWildcardType(parameter.getTypeAsString))
        .getOrElse(TypeConstants.UnresolvedType)
    }
    val parameterNode = NewMethodParameterIn()
      .name(parameter.getName.toString)
      .code(parameter.toString)
      .typeFullName(s"$typeFullName$maybeArraySuffix")
      .lineNumber(line(parameter))
      .columnNumber(column(parameter))
      .evaluationStrategy(EvaluationStrategies.BY_SHARING)
      .index(childNum)
      .order(childNum)
    val annotationAsts = parameter.getAnnotations.asScala.map(astForAnnotationExpr)
    val ast            = Ast(parameterNode)

    scopeStack.addToScope(parameter.getNameAsString, parameterNode)
    ast.withChildren(annotationAsts)
  }

  private def constructorFullName(typeDecl: Option[NewTypeDecl], signature: String): String = {
    val typeName = typeDecl.map(_.fullName).getOrElse(TypeConstants.UnresolvedType)
    s"$typeName.<init>:$signature"
  }

}

object AstCreator {
  def line(node: Node): Option[Integer] = {
    node.getBegin.map(x => Integer.valueOf(x.line)).toScala
  }

  def column(node: Node): Option[Integer] = {
    node.getBegin.map(x => Integer.valueOf(x.column)).toScala
  }
}
