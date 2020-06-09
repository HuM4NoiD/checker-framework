package org.checkerframework.dataflow.analysis;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.sun.org.apache.bcel.internal.classfile.Unknown;
import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.cfg.node.*;
import org.checkerframework.dataflow.util.PurityUtils;
import org.checkerframework.javacutil.AnnotationProvider;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypeAnnotationUtils;
import org.checkerframework.javacutil.TypesUtils;

/**
 * Collection of classes and helper functions to represent Java expressions about which the
 * org.checkerframework.dataflow analysis can possibly infer facts. Expressions include:
 *
 * <ul>
 *   <li>Field accesses (e.g., <em>o.f</em>)
 *   <li>Local variables (e.g., <em>l</em>)
 *   <li>This reference (e.g., <em>this</em>)
 *   <li>Pure method calls (e.g., <em>o.m()</em>)
 *   <li>Unknown other expressions to mark that something else was present.
 * </ul>
 */
public class FlowExpressions {

    /**
     * Returns the internal representation (as {@link FieldAccess}) of a {@link FieldAccessNode}.
     * Can contain {@link Unknown} as receiver.
     *
     * @return the internal representation (as {@link FieldAccess}) of a {@link FieldAccessNode}.
     *     Can contain {@link Unknown} as receiver.
     */
    public static FieldAccess internalReprOfFieldAccess(
            AnnotationProvider provider, FieldAccessNode node) {
        Receiver receiver;
        Node receiverNode = node.getReceiver();
        if (node.isStatic()) {
            receiver = new ClassName(receiverNode.getType());
        } else {
            receiver = internalReprOf(provider, receiverNode);
        }
        return new FieldAccess(receiver, node);
    }

    /**
     * Returns the internal representation (as {@link FieldAccess}) of a {@link FieldAccessNode}.
     * Can contain {@link Unknown} as receiver.
     *
     * @return the internal representation (as {@link FieldAccess}) of a {@link FieldAccessNode}.
     *     Can contain {@link Unknown} as receiver.
     */
    public static ArrayAccess internalReprOfArrayAccess(
            AnnotationProvider provider, ArrayAccessNode node) {
        Receiver receiver = internalReprOf(provider, node.getArray());
        Receiver index = internalReprOf(provider, node.getIndex());
        return new ArrayAccess(node.getType(), receiver, index);
    }

    /** @return internal representation (as {@link ArrayAccessExpr}) of {@link ArrayAccessNode} */
    public static ArrayAccessExpr internalReprOfArrayAccessExpr(
            AnnotationProvider provider, ArrayAccessNode node) {
        // TODO: get the following expressions from node: name (given by getArray()), index (given
        // by getIndex())
        node.getArray();
        node.getIndex();
        return new ArrayAccessExpr();
    }
    /**
     * We ignore operations such as widening and narrowing when computing the internal
     * representation.
     *
     * @return the internal representation (as {@link Receiver}) of any {@link Node}. Might contain
     *     {@link Unknown}.dnf install @xfce-desktop-environment
     */
    public static Receiver internalReprOf(AnnotationProvider provider, Node receiverNode) {
        return internalReprOf(provider, receiverNode, false);
    }

    /**
     * We ignore operations such as widening and narrowing when computing the internal
     * representation.
     *
     * @return the internal representation (as {@link Receiver}) of any {@link Node}. Might contain
     *     {@link Unknown}.
     */
    public static Receiver internalReprOf(
            AnnotationProvider provider, Node receiverNode, boolean allowNonDeterministic) {
        Receiver receiver = null;
        if (receiverNode instanceof FieldAccessNode) {
            FieldAccessNode fan = (FieldAccessNode) receiverNode;

            if (fan.getFieldName().equals("this")) {
                // For some reason, "className.this" is considered a field access.
                // We right this wrong here.
                receiver = new ThisReference(fan.getReceiver().getType());
            } else if (fan.getFieldName().equals("class")) {
                // "className.class" is considered a field access. This makes sense,
                // since .class is similar to a field access which is the equivalent
                // of a call to getClass(). However for the purposes of dataflow
                // analysis, and value stores, this is the equivalent of a ClassNameNode.
                receiver = new ClassName(fan.getReceiver().getType());
            } else {
                receiver = internalReprOfFieldAccess(provider, fan);
            }
        } else if (receiverNode instanceof ExplicitThisLiteralNode) {
            receiver = new ThisReference(receiverNode.getType());
        } else if (receiverNode instanceof ThisLiteralNode) {
            receiver = new ThisReference(receiverNode.getType());
        } else if (receiverNode instanceof SuperNode) {
            receiver = new ThisReference(receiverNode.getType());
        } else if (receiverNode instanceof LocalVariableNode) {
            LocalVariableNode lv = (LocalVariableNode) receiverNode;
            receiver = new LocalVariable(lv);
        } else if (receiverNode instanceof ArrayAccessNode) {
            ArrayAccessNode a = (ArrayAccessNode) receiverNode;
            receiver = internalReprOfArrayAccess(provider, a);
        } else if (receiverNode instanceof StringConversionNode) {
            // ignore string conversion
            return internalReprOf(provider, ((StringConversionNode) receiverNode).getOperand());
        } else if (receiverNode instanceof WideningConversionNode) {
            // ignore widening
            return internalReprOf(provider, ((WideningConversionNode) receiverNode).getOperand());
        } else if (receiverNode instanceof NarrowingConversionNode) {
            // ignore narrowing
            return internalReprOf(provider, ((NarrowingConversionNode) receiverNode).getOperand());
        } else if (receiverNode instanceof ClassNameNode) {
            ClassNameNode cn = (ClassNameNode) receiverNode;
            receiver = new ClassName(cn.getType());
        } else if (receiverNode instanceof ValueLiteralNode) {
            ValueLiteralNode vn = (ValueLiteralNode) receiverNode;
            receiver = new ValueLiteral(vn.getType(), vn);
        } else if (receiverNode instanceof ArrayCreationNode) {
            ArrayCreationNode an = (ArrayCreationNode) receiverNode;
            List<Receiver> dimensions = new ArrayList<>();
            for (Node dimension : an.getDimensions()) {
                dimensions.add(internalReprOf(provider, dimension, allowNonDeterministic));
            }
            List<Receiver> initializers = new ArrayList<>();
            for (Node initializer : an.getInitializers()) {
                initializers.add(internalReprOf(provider, initializer, allowNonDeterministic));
            }
            receiver = new ArrayCreation(an.getType(), dimensions, initializers);
        } else if (receiverNode instanceof MethodInvocationNode) {
            MethodInvocationNode mn = (MethodInvocationNode) receiverNode;
            MethodInvocationTree t = mn.getTree();
            if (t == null) {
                throw new BugInCF("Unexpected null tree for node: " + mn);
            }
            assert TreeUtils.isUseOfElement(t) : "@AssumeAssertion(nullness): tree kind";
            ExecutableElement invokedMethod = TreeUtils.elementFromUse(t);

            if (allowNonDeterministic || PurityUtils.isDeterministic(provider, invokedMethod)) {
                List<Receiver> parameters = new ArrayList<>();
                for (Node p : mn.getArguments()) {
                    parameters.add(internalReprOf(provider, p));
                }
                Receiver methodReceiver;
                if (ElementUtils.isStatic(invokedMethod)) {
                    methodReceiver = new ClassName(mn.getTarget().getReceiver().getType());
                } else {
                    methodReceiver = internalReprOf(provider, mn.getTarget().getReceiver());
                }
                receiver = new MethodCall(mn.getType(), invokedMethod, methodReceiver, parameters);
            }
        }

        if (receiver == null) {
            receiver = new Unknown(receiverNode.getType());
        }
        return receiver;
    }

    /** @return internal representation of any {@link Node} as {@link Expression} */
    public static Expression internalReprOfExpr(AnnotationProvider provider, Node receiverNode) {
        return internalReprOfExpr(provider, receiverNode, false);
    }

    /** @return internal representation of any {@link Node} as {@link Expression} */
    public static Expression internalReprOfExpr(
            AnnotationProvider provider, Node receiverNode, boolean allowNonDeterministic) {
        Expression expression = null;

        if (receiverNode instanceof FieldAccessNode) {
            FieldAccessNode node = (FieldAccessNode) receiverNode;

            if (node.getFieldName().equals("this")) {
                Name name = new Name(node.getReceiver().getType().toString());
                expression = new ThisExpr(name);
            } else if (node.getFieldName().equals("class")) {
                expression = new NameExpr(node.getType().toString());
            }
        } else if (receiverNode instanceof ExplicitThisLiteralNode
                || receiverNode instanceof ThisLiteralNode) {
            expression = new ThisExpr(new Name(receiverNode.getType().toString()));
        } else if (receiverNode instanceof SuperNode) {
            expression = new SuperExpr(new Name(receiverNode.getType().toString()));
        } else if (receiverNode instanceof LocalVariableNode) {
            LocalVariableNode node = (LocalVariableNode) receiverNode;
            // TODO: there is no Expression equivalent of LocalVariable
            expression = new NameExpr(node.getName());
        } else if (receiverNode instanceof ArrayAccessNode) {
            expression = new ArrayAccessExpr();
        } else if (receiverNode instanceof StringConversionNode) {
            expression =
                    internalReprOfExpr(
                            provider, ((StringConversionNode) receiverNode).getOperand());
        } else if (receiverNode instanceof WideningConversionNode) {
            expression =
                    internalReprOfExpr(
                            provider, ((WideningConversionNode) receiverNode).getOperand());
        } else if (receiverNode instanceof NarrowingConversionNode) {
            expression =
                    internalReprOfExpr(
                            provider, ((NarrowingConversionNode) receiverNode).getOperand());
        } else if (receiverNode instanceof ClassNameNode) {
            // TODO: Type from TypeMirror
            expression = new ClassExpr(getTypeFromTypeMirror(receiverNode.getType()));
        } else if (receiverNode instanceof ValueLiteralNode) {
            ValueLiteralNode node = (ValueLiteralNode) receiverNode;
            LiteralTree tree = node.getTree();
            // TODO: ValueLiteral represents all types of literals, whereas short is not available
            // for LiteralExpr
            if (node instanceof BooleanLiteralNode) {
                expression = new BooleanLiteralExpr(((BooleanLiteralNode) node).getValue());
            } else if (node instanceof NullLiteralNode) {
                expression = new NullLiteralExpr();
            } else if (node instanceof StringLiteralNode) {
                expression = new StringLiteralExpr(((StringLiteralNode) node).getValue());
            } else if (node instanceof IntegerLiteralNode) {
                String intLiteralString = String.valueOf(((IntegerLiteralNode) node).getValue());
                expression = new IntegerLiteralExpr(intLiteralString);
            } else if (node instanceof LongLiteralNode) {
                String longLiteralString = String.valueOf(((LongLiteralNode) node).getValue());
                expression = new LongLiteralExpr(longLiteralString);
            } else if (node instanceof CharacterLiteralNode) {
                expression = new CharLiteralExpr(((CharacterLiteralNode) node).getValue());
            } else if (node instanceof DoubleLiteralNode) {
                expression = new DoubleLiteralExpr(((DoubleLiteralNode) node).getValue());
            } else if (node instanceof FloatLiteralNode) {
                expression = new DoubleLiteralExpr(((FloatLiteralNode) node).getValue());
            }
        } else if (receiverNode instanceof ArrayCreationNode) {
            ArrayCreationNode node = (ArrayCreationNode) receiverNode;
            NodeList<ArrayCreationLevel> levels = new NodeList<>();
            NodeList<Expression> values = new NodeList<>();
            String typeString = node.getType().toString();
            for (Node dim : node.getDimensions()) {
                Expression dimExpression = internalReprOfExpr(provider, dim, allowNonDeterministic);
                levels.add(new ArrayCreationLevel(dimExpression));
            }
            for (Node initializer : node.getInitializers()) {
                Expression initExpression =
                        internalReprOfExpr(provider, initializer, allowNonDeterministic);
                values.add(initExpression);
            }
            ArrayInitializerExpr arrayInitializerExpr = new ArrayInitializerExpr(values);
            Type arrayType = getTypeFromTypeMirror(receiverNode.getType());
            expression = new ArrayCreationExpr(arrayType, levels, arrayInitializerExpr);
        } else if (receiverNode instanceof MethodInvocationNode) {
            MethodInvocationNode mn = (MethodInvocationNode) receiverNode;
            MethodInvocationTree t = mn.getTree();
            String name = mn.getTarget().getMethod().getSimpleName().toString();
            if (t == null) {
                throw new BugInCF("internalReprOfExpr: Unexpected null tree for node: " + mn);
            }
            assert TreeUtils.isUseOfElement(t) : "@AssumeAssertion(nullness): tree kind";
            ExecutableElement invokedMethod = TreeUtils.elementFromUse(t);

            if (allowNonDeterministic || PurityUtils.isDeterministic(provider, invokedMethod)) {
                NodeList<Expression> args = new NodeList<>();
                int i = 0;
                for (Node node : mn.getArguments()) {
                    args.add(internalReprOfExpr(provider, node));
                }
                Expression methodReceiver;
                if (ElementUtils.isStatic(invokedMethod)) {
                    // TODO: get Type from TypeMirror
                    // TODO: is ClassExpr equivalent to ClassName?
                    Type currentType = getTypeFromTypeMirror(receiverNode.getType());
                    methodReceiver = new ClassExpr(currentType);
                } else {
                    methodReceiver = internalReprOfExpr(provider, mn.getTarget().getReceiver());
                }
                expression = new MethodCallExpr(methodReceiver, name, args);
            }
        }
        // expression will be null if not recognised
        return expression;
    }

    /**
     * Returns the internal representation (as {@link Receiver}) of any {@link ExpressionTree}.
     * Might contain {@link Unknown}.
     *
     * @return the internal representation (as {@link Receiver}) of any {@link ExpressionTree}.
     *     Might contain {@link Unknown}.
     */
    public static Receiver internalReprOf(
            AnnotationProvider provider, ExpressionTree receiverTree) {
        return internalReprOf(provider, receiverTree, true);
    }
    /**
     * We ignore operations such as widening and narrowing when computing the internal
     * representation.
     *
     * @return the internal representation (as {@link Receiver}) of any {@link ExpressionTree}.
     *     Might contain {@link Unknown}.
     */
    public static Receiver internalReprOf(
            AnnotationProvider provider,
            ExpressionTree receiverTree,
            boolean allowNonDeterministic) {
        Receiver receiver;
        switch (receiverTree.getKind()) {
            case ARRAY_ACCESS:
                ArrayAccessTree a = (ArrayAccessTree) receiverTree;
                Receiver arrayAccessExpression = internalReprOf(provider, a.getExpression());
                Receiver index = internalReprOf(provider, a.getIndex());
                receiver = new ArrayAccess(TreeUtils.typeOf(a), arrayAccessExpression, index);
                break;
            case BOOLEAN_LITERAL:
            case CHAR_LITERAL:
            case DOUBLE_LITERAL:
            case FLOAT_LITERAL:
            case INT_LITERAL:
            case LONG_LITERAL:
            case NULL_LITERAL:
            case STRING_LITERAL:
                LiteralTree vn = (LiteralTree) receiverTree;
                receiver = new ValueLiteral(TreeUtils.typeOf(receiverTree), vn.getValue());
                break;
            case NEW_ARRAY:
                NewArrayTree newArrayTree = (NewArrayTree) receiverTree;
                List<Receiver> dimensions = new ArrayList<>();
                if (newArrayTree.getDimensions() != null) {
                    for (ExpressionTree dimension : newArrayTree.getDimensions()) {
                        dimensions.add(internalReprOf(provider, dimension, allowNonDeterministic));
                    }
                }
                List<Receiver> initializers = new ArrayList<>();
                if (newArrayTree.getInitializers() != null) {
                    for (ExpressionTree initializer : newArrayTree.getInitializers()) {
                        initializers.add(
                                internalReprOf(provider, initializer, allowNonDeterministic));
                    }
                }

                receiver =
                        new ArrayCreation(TreeUtils.typeOf(receiverTree), dimensions, initializers);
                break;
            case METHOD_INVOCATION:
                MethodInvocationTree mn = (MethodInvocationTree) receiverTree;
                assert TreeUtils.isUseOfElement(mn) : "@AssumeAssertion(nullness): tree kind";
                ExecutableElement invokedMethod = TreeUtils.elementFromUse(mn);
                if (PurityUtils.isDeterministic(provider, invokedMethod) || allowNonDeterministic) {
                    List<Receiver> parameters = new ArrayList<>();
                    for (ExpressionTree p : mn.getArguments()) {
                        parameters.add(internalReprOf(provider, p));
                    }
                    Receiver methodReceiver;
                    if (ElementUtils.isStatic(invokedMethod)) {
                        methodReceiver = new ClassName(TreeUtils.typeOf(mn.getMethodSelect()));
                    } else {
                        ExpressionTree methodReceiverTree = TreeUtils.getReceiverTree(mn);
                        if (methodReceiverTree != null) {
                            methodReceiver = internalReprOf(provider, methodReceiverTree);
                        } else {
                            methodReceiver = internalReprOfImplicitReceiver(invokedMethod);
                        }
                    }
                    TypeMirror type = TreeUtils.typeOf(mn);
                    receiver = new MethodCall(type, invokedMethod, methodReceiver, parameters);
                } else {
                    receiver = null;
                }
                break;
            case MEMBER_SELECT:
                receiver = internalReprOfMemberSelect(provider, (MemberSelectTree) receiverTree);
                break;
            case IDENTIFIER:
                IdentifierTree identifierTree = (IdentifierTree) receiverTree;
                TypeMirror typeOfId = TreeUtils.typeOf(identifierTree);
                if (identifierTree.getName().contentEquals("this")
                        || identifierTree.getName().contentEquals("super")) {
                    receiver = new ThisReference(typeOfId);
                    break;
                }
                assert TreeUtils.isUseOfElement(identifierTree)
                        : "@AssumeAssertion(nullness): tree kind";
                Element ele = TreeUtils.elementFromUse(identifierTree);
                if (ElementUtils.isClassElement(ele)) {
                    receiver = new ClassName(ele.asType());
                    break;
                }
                switch (ele.getKind()) {
                    case LOCAL_VARIABLE:
                    case RESOURCE_VARIABLE:
                    case EXCEPTION_PARAMETER:
                    case PARAMETER:
                        receiver = new LocalVariable(ele);
                        break;
                    case FIELD:
                        // Implicit access expression, such as "this" or a class name
                        Receiver fieldAccessExpression;
                        @SuppressWarnings(
                                "nullness:dereference.of.nullable") // a field has enclosing class
                        TypeMirror enclosingType = ElementUtils.enclosingClass(ele).asType();
                        if (ElementUtils.isStatic(ele)) {
                            fieldAccessExpression = new ClassName(enclosingType);
                        } else {
                            fieldAccessExpression = new ThisReference(enclosingType);
                        }
                        receiver =
                                new FieldAccess(
                                        fieldAccessExpression, typeOfId, (VariableElement) ele);
                        break;
                    default:
                        receiver = null;
                }
                break;
            case UNARY_PLUS:
                return internalReprOf(
                        provider,
                        ((UnaryTree) receiverTree).getExpression(),
                        allowNonDeterministic);
            default:
                receiver = null;
        }

        if (receiver == null) {
            receiver = new Unknown(TreeUtils.typeOf(receiverTree));
        }
        return receiver;
    }

    /**
     * @param provider
     * @param recieverTree
     * @return internal representation of {@link ExpressionTree} as {@link Expression}
     */
    public static Expression internalReprOfExpr(
            AnnotationProvider provider, ExpressionTree recieverTree) {
        return internalReprOfExpr(provider, recieverTree, false);
    }

    /**
     * @param provider
     * @param receiverTree
     * @return internal representation of {@link ExpressionTree} as {@link Expression}
     */
    public static Expression internalReprOfExpr(
            AnnotationProvider provider,
            ExpressionTree receiverTree,
            boolean allowNonDeterministic) {
        Expression expression = null;
        switch (receiverTree.getKind()) {
            case ARRAY_ACCESS:
                {
                    ArrayAccessTree tree = (ArrayAccessTree) receiverTree;
                    Expression arrayAccessExpression =
                            internalReprOfExpr(provider, tree.getExpression());
                    Expression index = internalReprOfExpr(provider, tree.getIndex());
                    expression = new ArrayAccessExpr(arrayAccessExpression, index);
                    break;
                }
            case BOOLEAN_LITERAL:
                {
                    Boolean value = (Boolean) ((LiteralTree) receiverTree).getValue();
                    expression = new BooleanLiteralExpr(value);
                    break;
                }
            case CHAR_LITERAL:
                {
                    Character value = (Character) ((LiteralTree) receiverTree).getValue();
                    expression = new CharLiteralExpr(value);
                    break;
                }
            case INT_LITERAL:
                {
                    Integer value = (Integer) ((LiteralTree) receiverTree).getValue();
                    expression = new IntegerLiteralExpr(String.valueOf(value));
                    break;
                }
            case LONG_LITERAL:
                {
                    Long value = (Long) ((LiteralTree) receiverTree).getValue();
                    expression = new LongLiteralExpr(String.valueOf(value));
                    break;
                }
            case STRING_LITERAL:
                {
                    String value = ((LiteralTree) receiverTree).getValue().toString();
                    expression = new StringLiteralExpr(value);
                    break;
                }
            case NULL_LITERAL:
                expression = new NullLiteralExpr();
                break;
            case FLOAT_LITERAL:
            case DOUBLE_LITERAL:
                {
                    Double value = (Double) ((LiteralTree) receiverTree).getValue();
                    expression = new DoubleLiteralExpr(value);
                    break;
                }
            case NEW_ARRAY:
                {
                    NewArrayTree nwt = (NewArrayTree) receiverTree;
                    NodeList<ArrayCreationLevel> levels = new NodeList<>();
                    NodeList<Expression> values = new NodeList<>();

                    if (nwt.getDimensions() != null) {
                        for (ExpressionTree dim : nwt.getDimensions()) {
                            Expression dimExpression =
                                    internalReprOfExpr(provider, dim, allowNonDeterministic);
                            levels.add(new ArrayCreationLevel(dimExpression));
                        }
                    }
                    if (nwt.getInitializers() != null) {
                        for (ExpressionTree initializer : nwt.getInitializers()) {
                            Expression initExpression =
                                    internalReprOfExpr(
                                            provider, initializer, allowNonDeterministic);
                            values.add(initExpression);
                        }
                    }
                    ArrayInitializerExpr arrayInitializerExpr = new ArrayInitializerExpr(values);
                    Type type = getTypeFromTypeMirror(TreeUtils.typeOf(receiverTree));
                    // TODO: get Type from TypeMirror
                    expression = new ArrayCreationExpr(type, levels, arrayInitializerExpr);
                    break;
                }
            case METHOD_INVOCATION:
                {
                    MethodInvocationTree mn = (MethodInvocationTree) receiverTree;
                    assert TreeUtils.isUseOfElement(mn) : "@AssumeAssertion(nullness): tree kind";
                    ExecutableElement invokedMethod = TreeUtils.elementFromUse(mn);
                    if (PurityUtils.isDeterministic(provider, invokedMethod)
                            || allowNonDeterministic) {
                        NodeList<Expression> params = new NodeList<>();
                        for (ExpressionTree p : mn.getArguments()) {
                            params.add(internalReprOfExpr(provider, p));
                        }
                        Expression methodScope;

                        if (ElementUtils.isStatic(invokedMethod)) {
                            Type classNameType =
                                    getTypeFromTypeMirror(TreeUtils.typeOf(mn.getMethodSelect()));
                            methodScope = new ClassExpr(classNameType);
                        } else {
                            ExpressionTree methodReceiverTree = TreeUtils.getReceiverTree(mn);
                            if (methodReceiverTree != null) {
                                methodScope = internalReprOfExpr(provider, methodReceiverTree);
                            } else {
                                methodScope = internalReprOfImplicitReceiverExpr(invokedMethod);
                            }
                        }
                        List<? extends Tree> typeArgs = mn.getTypeArguments();
                        NodeList<Type> typeArgsNodeList = new NodeList<>();
                        for (Tree tree : typeArgs) {
                            Type type = getTypeFromTypeMirror(TreeUtils.typeOf(tree));
                            typeArgsNodeList.add(type);
                            TreeUtils.typeOf(tree);
                        }
                        // TODO: getMethodName from method MethodInvocationTree
                        // expression = new MethodCallExpr(methodScope, typeArgsNodeList, , params);
                    } else {
                        expression = null;
                    }
                    break;
                }
            case MEMBER_SELECT:
                expression =
                        internalReprOfMemberSelectExpr(provider, (MemberSelectTree) receiverTree);
                break;
            case IDENTIFIER:
                {
                    IdentifierTree it = (IdentifierTree) receiverTree;
                    TypeMirror idType = TreeUtils.typeOf(it);
                    if (it.getName().contentEquals("this")) {
                        expression = new ThisExpr(new Name(it.getName().toString()));
                        break;
                    }
                    assert TreeUtils.isUseOfElement(it) : "@AssumeAssertion(nullness): tree kind";
                    Element element = TreeUtils.elementFromTree(it);
                    if (ElementUtils.isClassElement(element)) {
                        // TODO: get Type from TypeMirror
                        expression = new ClassExpr(getTypeFromTypeMirror(element.asType()));
                        break;
                    }
                    switch (element.getKind()) {
                        case LOCAL_VARIABLE:
                        case RESOURCE_VARIABLE:
                        case EXCEPTION_PARAMETER:
                        case PARAMETER:
                            // TODO: no LocalVariableExpr exists equivalent to LocalVariable
                            expression = new NameExpr(it.getName().toString());
                            break;
                        case FIELD:
                            Expression fieldAccessScope;
                            @SuppressWarnings(
                                    "nullness:dereference.of.nullable") // a field has enclosing
                            // class
                            TypeMirror enclosingType =
                                    ElementUtils.enclosingClass(element).asType();
                            if (ElementUtils.isStatic(element)) {
                                fieldAccessScope =
                                        new ClassExpr(getTypeFromTypeMirror(enclosingType));
                            } else {
                                fieldAccessScope = new ThisExpr(new Name(enclosingType.toString()));
                            }
                            expression =
                                    new FieldAccessExpr(fieldAccessScope, it.getName().toString());
                            break;
                        default:
                            expression = null;
                    }
                    break;
                }
            case UNARY_PLUS:
                return internalReprOfExpr(
                        provider,
                        ((UnaryTree) receiverTree).getExpression(),
                        allowNonDeterministic);
            default:
                expression = null;
        }

        // TODO: Expression has no Unknown equivalent
        return expression;
    }

    /**
     * Utility method to get {@link Type} from {@link TypeMirror}
     *
     * @param typeMirror
     * @return {@link Type} equivalent of {@link TypeMirror}
     */
    public static Type getTypeFromTypeMirror(TypeMirror typeMirror) {
        Type type = null;
        if (typeMirror instanceof PrimitiveType) {
            switch (((PrimitiveType) typeMirror).getKind()) {
                case BOOLEAN:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN);
                    break;
                case CHAR:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.CHAR);
                    break;
                case BYTE:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.BYTE);
                    break;
                case SHORT:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.SHORT);
                    break;
                case INT:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.INT);
                    break;
                case LONG:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.LONG);
                    break;
                case FLOAT:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.FLOAT);
                    break;
                case DOUBLE:
                    type =
                            new com.github.javaparser.ast.type.PrimitiveType(
                                    com.github.javaparser.ast.type.PrimitiveType.Primitive.DOUBLE);
                    break;
            }
        } else if (typeMirror instanceof ReferenceType) {
            if (typeMirror instanceof DeclaredType) {
                DeclaredType dType = (DeclaredType) typeMirror;
                List<? extends TypeMirror> paramTypeMirrors = dType.getTypeArguments();
                String fqName = dType.asElement().toString();
                int lastDotIndex = fqName.lastIndexOf('.');
                String className = fqName.substring(lastDotIndex + 1);
                if (paramTypeMirrors == null || paramTypeMirrors.size() == 0) {
                    // This type does not include type parameters like MyClass<String, Integer>
                    // TODO: get Outer ClassOrInterfaceType as scope
                    type =
                            new ClassOrInterfaceType(
                                    /*Add Outer ClassOrInterfaceType here*/ null, fqName);
                } else {
                    NodeList<Type> typeArgs = new NodeList<>();
                    for (int i = 0; i < paramTypeMirrors.size(); ++i) {
                        TypeMirror param = paramTypeMirrors.get(i);
                        Type paramType = getTypeFromTypeMirror(param);
                        typeArgs.add(paramType);
                    }
                    SimpleName name = new SimpleName(className);
                    // TODO: get Outer ClassOrInterfaceType as scope
                    type =
                            new ClassOrInterfaceType(
                                    /*Add Outer ClassOrInterfaceType here*/ null, name, typeArgs);
                }
            } else if (typeMirror instanceof ArrayType) {
                type = null;
            }
        }
        return type;
    }

    /**
     * Returns the implicit receiver of ele.
     *
     * <p>Returns either a new ClassName or a new ThisReference depending on whether ele is static
     * or not. The passed element must be a field, method, or class.
     *
     * @param ele field, method, or class
     * @return either a new ClassName or a new ThisReference depending on whether ele is static or
     *     not
     */
    public static Receiver internalReprOfImplicitReceiver(Element ele) {
        TypeElement enclosingClass = ElementUtils.enclosingClass(ele);
        if (enclosingClass == null) {
            throw new BugInCF(
                    "internalReprOfImplicitReceiver's arg has no enclosing class: " + ele);
        }
        TypeMirror enclosingType = enclosingClass.asType();
        if (ElementUtils.isStatic(ele)) {
            return new ClassName(enclosingType);
        } else {
            return new ThisReference(enclosingType);
        }
    }

    /**
     * returns {@link ClassExpr} or {@link ThisExpr} for the enclosing type
     *
     * @param element
     * @return {@link ClassExpr} if static member of method or {@link ThisExpr}
     */
    public static Expression internalReprOfImplicitReceiverExpr(Element element) {
        TypeElement enclosingClass = ElementUtils.enclosingClass(element);
        if (enclosingClass == null) {
            throw new BugInCF(
                    "internalReprOfImplicitReceiver's arg has no enclosing class: " + element);
        }
        TypeMirror enclosingType = enclosingClass.asType();
        if (ElementUtils.isStatic(element)) {
            return new ClassExpr(getTypeFromTypeMirror(enclosingType));
        } else {
            return new ThisExpr(new Name(enclosingType.toString()));
        }
    }

    /**
     * Returns either a new ClassName or ThisReference Receiver object for the enclosingType.
     *
     * <p>The Tree should be an expression or a statement that does not have a receiver or an
     * implicit receiver. For example, a local variable declaration.
     *
     * @param path TreePath to tree
     * @param enclosingType type of the enclosing type
     * @return a new ClassName or ThisReference that is a Receiver object for the enclosingType
     */
    public static Receiver internalReprOfPseudoReceiver(TreePath path, TypeMirror enclosingType) {
        if (TreeUtils.isTreeInStaticScope(path)) {
            return new ClassName(enclosingType);
        } else {
            return new ThisReference(enclosingType);
        }
    }

    /**
     * returns {@link ClassExpr} or {@link ThisExpr} for the enclosing type
     *
     * @return {@link ClassExpr} if static member of method or {@link ThisExpr}
     */
    public static Expression internalReprOfPseudoReceiverExpr(
            TreePath path, TypeMirror enclosingType) {
        if (TreeUtils.isTreeInStaticScope(path)) {
            return new ClassExpr(getTypeFromTypeMirror(enclosingType));
        } else {
            return new ThisExpr(new Name(enclosingType.toString()));
        }
    }

    private static Receiver internalReprOfMemberSelect(
            AnnotationProvider provider, MemberSelectTree memberSelectTree) {
        TypeMirror expressionType = TreeUtils.typeOf(memberSelectTree.getExpression());
        if (TreeUtils.isClassLiteral(memberSelectTree)) {
            return new ClassName(expressionType);
        }
        assert TreeUtils.isUseOfElement(memberSelectTree) : "@AssumeAssertion(nullness): tree kind";
        Element ele = TreeUtils.elementFromUse(memberSelectTree);
        if (ElementUtils.isClassElement(ele)) {
            // o instanceof MyClass.InnerClass
            // o instanceof MyClass.InnerInterface
            TypeMirror selectType = TreeUtils.typeOf(memberSelectTree);
            return new ClassName(selectType);
        }
        switch (ele.getKind()) {
            case METHOD:
            case CONSTRUCTOR:
                return internalReprOf(provider, memberSelectTree.getExpression());
            case ENUM_CONSTANT:
            case FIELD:
                TypeMirror fieldType = TreeUtils.typeOf(memberSelectTree);
                Receiver r = internalReprOf(provider, memberSelectTree.getExpression());
                return new FieldAccess(r, fieldType, (VariableElement) ele);
            default:
                throw new BugInCF("Unexpected element kind: %s element: %s", ele.getKind(), ele);
        }
    }

    /**
     * To get the Receiver expression for a member/field Access
     *
     * @param provider
     * @param memberSelectTree
     * @return {@link Expression} that represents receiver of expression
     */
    public static Expression internalReprOfMemberSelectExpr(
            AnnotationProvider provider, MemberSelectTree memberSelectTree) {
        TypeMirror expressionType = TreeUtils.typeOf(memberSelectTree.getExpression());

        if (TreeUtils.isClassLiteral(memberSelectTree)) {
            return new ClassExpr(getTypeFromTypeMirror(expressionType));
        }
        assert TreeUtils.isUseOfElement(memberSelectTree) : "@AssumeAssertion(nullness): tree kind";
        Element element = TreeUtils.elementFromUse(memberSelectTree);
        if (ElementUtils.isClassElement(element)) {
            // o instanceof MyClass.InnerClass
            // o instanceof MyClass.InnerInterface
            TypeMirror selectType = TreeUtils.typeOf(memberSelectTree);
            return new ClassExpr(getTypeFromTypeMirror(selectType));
        }
        switch (element.getKind()) {
            case METHOD:
            case CONSTRUCTOR:
                return internalReprOfExpr(provider, memberSelectTree.getExpression());
            case ENUM_CONSTANT:
            case FIELD:
                TypeMirror fieldType = TreeUtils.typeOf(memberSelectTree);
                Expression scope = internalReprOfExpr(provider, memberSelectTree.getExpression());
                String memberName = memberSelectTree.getIdentifier().toString();
                return new FieldAccessExpr(scope, memberName);
            default:
                throw new BugInCF(
                        "Unexpected element kind: %s element: %s", element.getKind(), element);
        }
    }

    /**
     * Returns Receiver objects for the formal parameters of the method in which path is enclosed.
     *
     * @param annotationProvider annotationProvider
     * @param path TreePath that is enclosed by the method
     * @return list of Receiver objects for the formal parameters of the method in which path is
     *     enclosed, {@code null} otherwise
     */
    public static @Nullable List<Receiver> getParametersOfEnclosingMethod(
            AnnotationProvider annotationProvider, TreePath path) {
        MethodTree methodTree = TreeUtils.enclosingMethod(path);
        if (methodTree == null) {
            return null;
        }
        List<Receiver> internalArguments = new ArrayList<>();
        for (VariableTree arg : methodTree.getParameters()) {
            internalArguments.add(internalReprOf(annotationProvider, new LocalVariableNode(arg)));
        }
        return internalArguments;
    }

    /**
     * Returns the list of {@link Expression}s for the formal parameters of a method
     *
     * @param annotationProvider
     * @param path TreePath enclosed by the method
     * @return List of Expressions that represent formal parameters
     */
    public static List<Expression> getParamExprOfEnclosingMethod(
            AnnotationProvider annotationProvider, TreePath path) {
        MethodTree methodTree = TreeUtils.enclosingMethod(path);
        if (methodTree == null) {
            return null;
        }
        List<Expression> internalArguments = new ArrayList<>();
        for (VariableTree arg : methodTree.getParameters()) {
            internalArguments.add(
                    internalReprOfExpr(annotationProvider, new LocalVariableNode(arg)));
        }
        return internalArguments;
    }
    /**
     * The poorly-named Receiver class is actually a Java AST. Each subclass represents a different
     * type of expression, such as MethodCall, ArrayAccess, LocalVariable, etc.
     */
    public abstract static class Receiver {
        /** The type of this expression. */
        protected final TypeMirror type;

        /**
         * Create a Receiver (a Java AST node representing an expression).
         *
         * @param type the type of the expression
         */
        protected Receiver(TypeMirror type) {
            assert type != null;
            this.type = type;
        }

        public TypeMirror getType() {
            return type;
        }

        public abstract boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz);

        public boolean containsUnknown() {
            return containsOfClass(Unknown.class);
        }

        /**
         * Returns true if and only if the value this expression stands for cannot be changed (with
         * respect to ==) by a method call. This is the case for local variables, the self reference
         * as well as final field accesses for whose receiver {@link #isUnassignableByOtherCode} is
         * true.
         *
         * @see #isUnmodifiableByOtherCode
         */
        public abstract boolean isUnassignableByOtherCode();

        /**
         * Returns true if and only if the value this expression stands for cannot be changed by a
         * method call, including changes to any of its fields.
         *
         * <p>Approximately, this returns true if the expression is {@link
         * #isUnassignableByOtherCode} and its type is immutable.
         *
         * @see #isUnassignableByOtherCode
         */
        public abstract boolean isUnmodifiableByOtherCode();

        /**
         * Returns true if and only if the two receiver are syntactically identical.
         *
         * @return true if and only if the two receiver are syntactically identical
         */
        public boolean syntacticEquals(Receiver other) {
            return other == this;
        }

        /**
         * Returns true if and only if this receiver contains a receiver that is syntactically equal
         * to {@code other}.
         *
         * @return true if and only if this receiver contains a receiver that is syntactically equal
         *     to {@code other}
         */
        public boolean containsSyntacticEqualReceiver(Receiver other) {
            return syntacticEquals(other);
        }

        /**
         * Returns true if and only if {@code other} appears anywhere in this receiver or an
         * expression appears in this receiver such that {@code other} might alias this expression,
         * and that expression is modifiable.
         *
         * <p>This is always true, except for cases where the Java type information prevents
         * aliasing and none of the subexpressions can alias 'other'.
         */
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            return this.equals(other) || store.canAlias(this, other);
        }

        /**
         * Print this verbosely, for debugging.
         *
         * @return a verbose printed representation of this
         */
        public String debugToString() {
            return String.format(
                    "Receiver (%s) %s type=%s", getClass().getSimpleName(), toString(), type);
        }
    }

    public static class FieldAccess extends Receiver {
        protected final Receiver receiver;
        protected final VariableElement field;

        public Receiver getReceiver() {
            return receiver;
        }

        public VariableElement getField() {
            return field;
        }

        public FieldAccess(Receiver receiver, FieldAccessNode node) {
            super(node.getType());
            this.receiver = receiver;
            this.field = node.getElement();
        }

        public FieldAccess(Receiver receiver, TypeMirror type, VariableElement fieldElement) {
            super(type);
            this.receiver = receiver;
            this.field = fieldElement;
        }

        public boolean isFinal() {
            return ElementUtils.isFinal(field);
        }

        public boolean isStatic() {
            return ElementUtils.isStatic(field);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof FieldAccess)) {
                return false;
            }
            FieldAccess fa = (FieldAccess) obj;
            return fa.getField().equals(getField()) && fa.getReceiver().equals(getReceiver());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getField(), getReceiver());
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            return super.containsModifiableAliasOf(store, other)
                    || receiver.containsModifiableAliasOf(store, other);
        }

        @Override
        public boolean containsSyntacticEqualReceiver(Receiver other) {
            return syntacticEquals(other) || receiver.containsSyntacticEqualReceiver(other);
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            if (!(other instanceof FieldAccess)) {
                return false;
            }
            FieldAccess fa = (FieldAccess) other;
            return super.syntacticEquals(other)
                    || (fa.getField().equals(getField())
                            && fa.getReceiver().syntacticEquals(getReceiver()));
        }

        @Override
        public String toString() {
            if (receiver instanceof ClassName) {
                return receiver.getType() + "." + field;
            } else {
                return receiver + "." + field;
            }
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            return getClass() == clazz || receiver.containsOfClass(clazz);
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return isFinal() && getReceiver().isUnassignableByOtherCode();
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return isUnassignableByOtherCode()
                    && TypesUtils.isImmutableTypeInJdk(getReceiver().type);
        }
    }

    public static class ThisReference extends Receiver {
        public ThisReference(TypeMirror type) {
            super(type);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            return obj instanceof ThisReference;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "this";
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            return getClass() == clazz;
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            return other instanceof ThisReference;
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return true;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return TypesUtils.isImmutableTypeInJdk(type);
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            return false; // 'this' is not modifiable
        }
    }

    /**
     * A ClassName represents the occurrence of a class as part of a static field access or method
     * invocation.
     */
    public static class ClassName extends Receiver {
        private final String typeString;

        public ClassName(TypeMirror type) {
            super(type);
            typeString = type.toString();
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof ClassName)) {
                return false;
            }
            ClassName other = (ClassName) obj;
            return typeString.equals(other.typeString);
        }

        @Override
        public int hashCode() {
            return Objects.hash(typeString);
        }

        @Override
        public String toString() {
            return typeString + ".class";
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            return getClass() == clazz;
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            return this.equals(other);
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return true;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return true;
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            return false; // not modifiable
        }
    }

    public static class Unknown extends Receiver {
        public Unknown(TypeMirror type) {
            super(type);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            return obj == this;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return "?";
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            return true;
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            return getClass() == clazz;
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return false;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return false;
        }
    }

    public static class LocalVariable extends Receiver {
        protected final Element element;

        public LocalVariable(LocalVariableNode localVar) {
            super(localVar.getType());
            this.element = localVar.getElement();
        }

        public LocalVariable(Element elem) {
            super(ElementUtils.getType(elem));
            this.element = elem;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof LocalVariable)) {
                return false;
            }

            LocalVariable other = (LocalVariable) obj;
            VarSymbol vs = (VarSymbol) element;
            VarSymbol vsother = (VarSymbol) other.element;
            // The code below isn't just return vs.equals(vsother) because an element might be
            // different between subcheckers.  The owner of a lambda parameter is the enclosing
            // method, so a local variable and a lambda parameter might have the same name and the
            // same owner.  pos is used to differentiate this case.
            return vs.pos == vsother.pos
                    && vsother.name.contentEquals(vs.name)
                    && vsother.owner.toString().equals(vs.owner.toString());
        }

        public Element getElement() {
            return element;
        }

        @Override
        public int hashCode() {
            VarSymbol vs = (VarSymbol) element;
            return Objects.hash(
                    vs.name.toString(),
                    TypeAnnotationUtils.unannotatedType(vs.type).toString(),
                    vs.owner.toString());
        }

        @Override
        public String toString() {
            return element.toString();
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            return getClass() == clazz;
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            if (!(other instanceof LocalVariable)) {
                return false;
            }
            LocalVariable l = (LocalVariable) other;
            return l.equals(this);
        }

        @Override
        public boolean containsSyntacticEqualReceiver(Receiver other) {
            return syntacticEquals(other);
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return true;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return TypesUtils.isImmutableTypeInJdk(((VarSymbol) element).type);
        }
    }

    /** FlowExpression.Receiver for literals. */
    public static class ValueLiteral extends Receiver {

        /** The value of the literal. */
        protected final @Nullable Object value;

        /**
         * Creates a ValueLiteral from the node with the given type.
         *
         * @param type type of the literal
         * @param node the literal represents by this {@link ValueLiteral}
         */
        public ValueLiteral(TypeMirror type, ValueLiteralNode node) {
            super(type);
            value = node.getValue();
        }

        /**
         * Creates a ValueLiteral where the value is {@code value} that has the given type.
         *
         * @param type type of the literal
         * @param value the literal value
         */
        public ValueLiteral(TypeMirror type, Object value) {
            super(type);
            this.value = value;
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            return getClass() == clazz;
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return true;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return true;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof ValueLiteral)) {
                return false;
            }
            ValueLiteral other = (ValueLiteral) obj;
            // TODO:  Can this string comparison be cleaned up?
            // Cannot use Types.isSameType(type, other.type) because we don't have a Types object.
            return type.toString().equals(other.type.toString())
                    && Objects.equals(value, other.value);
        }

        @Override
        public String toString() {
            if (TypesUtils.isString(type)) {
                return "\"" + value + "\"";
            } else if (type.getKind() == TypeKind.LONG) {
                assert value != null : "@AssumeAssertion(nullness): invariant";
                return value.toString() + "L";
            } else if (type.getKind() == TypeKind.CHAR) {
                return "\'" + value + "\'";
            }
            return value == null ? "null" : value.toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, type.toString());
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            return this.equals(other);
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            return false; // not modifiable
        }

        /**
         * Returns the value of this literal.
         *
         * @return the value of this literal
         */
        public @Nullable Object getValue() {
            return value;
        }
    }

    /** A call to a @Deterministic method. */
    public static class MethodCall extends Receiver {

        protected final Receiver receiver;
        protected final List<Receiver> parameters;
        protected final ExecutableElement method;

        public MethodCall(
                TypeMirror type,
                ExecutableElement method,
                Receiver receiver,
                List<Receiver> parameters) {
            super(type);
            this.receiver = receiver;
            this.parameters = parameters;
            this.method = method;
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            if (getClass() == clazz) {
                return true;
            }
            if (receiver.containsOfClass(clazz)) {
                return true;
            }
            for (Receiver p : parameters) {
                if (p.containsOfClass(clazz)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the method call receiver (for inspection only - do not modify).
         *
         * @return the method call receiver (for inspection only - do not modify)
         */
        public Receiver getReceiver() {
            return receiver;
        }

        /**
         * Returns the method call parameters (for inspection only - do not modify any of the
         * parameters).
         *
         * @return the method call parameters (for inspection only - do not modify any of the
         *     parameters)
         */
        public List<Receiver> getParameters() {
            return Collections.unmodifiableList(parameters);
        }

        /**
         * Returns the ExecutableElement for the method call.
         *
         * @return the ExecutableElement for the method call
         */
        public ExecutableElement getElement() {
            return method;
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            // There is no need to check that the method is deterministic, because a MethodCall is
            // only created for deterministic methods.
            return receiver.isUnmodifiableByOtherCode()
                    && parameters.stream().allMatch(Receiver::isUnmodifiableByOtherCode);
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return isUnassignableByOtherCode();
        }

        @Override
        public boolean containsSyntacticEqualReceiver(Receiver other) {
            return syntacticEquals(other) || receiver.syntacticEquals(other);
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            if (!(other instanceof MethodCall)) {
                return false;
            }
            MethodCall otherMethod = (MethodCall) other;
            if (!receiver.syntacticEquals(otherMethod.receiver)) {
                return false;
            }
            if (parameters.size() != otherMethod.parameters.size()) {
                return false;
            }
            int i = 0;
            for (Receiver p : parameters) {
                if (!p.syntacticEquals(otherMethod.parameters.get(i))) {
                    return false;
                }
                i++;
            }
            return method.equals(otherMethod.method);
        }

        public boolean containsSyntacticEqualParameter(LocalVariable var) {
            for (Receiver p : parameters) {
                if (p.containsSyntacticEqualReceiver(var)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            if (receiver.containsModifiableAliasOf(store, other)) {
                return true;
            }
            for (Receiver p : parameters) {
                if (p.containsModifiableAliasOf(store, other)) {
                    return true;
                }
            }
            return false; // the method call itself is not modifiable
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof MethodCall)) {
                return false;
            }
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                return this == obj;
            }
            MethodCall other = (MethodCall) obj;
            return parameters.equals(other.parameters)
                    && receiver.equals(other.receiver)
                    && method.equals(other.method);
        }

        @Override
        public int hashCode() {
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                return super.hashCode();
            }
            return Objects.hash(method, receiver, parameters);
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            if (receiver instanceof ClassName) {
                result.append(receiver.getType());
            } else {
                result.append(receiver);
            }
            result.append(".");
            String methodName = method.getSimpleName().toString();
            result.append(methodName);
            result.append("(");
            boolean first = true;
            for (Receiver p : parameters) {
                if (!first) {
                    result.append(", ");
                }
                result.append(p.toString());
                first = false;
            }
            result.append(")");
            return result.toString();
        }
    }

    /** An array access. */
    public static class ArrayAccess extends Receiver {

        protected final Receiver receiver;
        protected final Receiver index;

        public ArrayAccess(TypeMirror type, Receiver receiver, Receiver index) {
            super(type);
            this.receiver = receiver;
            this.index = index;
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            if (getClass() == clazz) {
                return true;
            }
            if (receiver.containsOfClass(clazz)) {
                return true;
            }
            return index.containsOfClass(clazz);
        }

        public Receiver getReceiver() {
            return receiver;
        }

        public Receiver getIndex() {
            return index;
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return false;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return false;
        }

        @Override
        public boolean containsSyntacticEqualReceiver(Receiver other) {
            return syntacticEquals(other)
                    || receiver.syntacticEquals(other)
                    || index.syntacticEquals(other);
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            if (!(other instanceof ArrayAccess)) {
                return false;
            }
            ArrayAccess otherArrayAccess = (ArrayAccess) other;
            if (!receiver.syntacticEquals(otherArrayAccess.receiver)) {
                return false;
            }
            return index.syntacticEquals(otherArrayAccess.index);
        }

        @Override
        public boolean containsModifiableAliasOf(Store<?> store, Receiver other) {
            if (receiver.containsModifiableAliasOf(store, other)) {
                return true;
            }
            return index.containsModifiableAliasOf(store, other);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof ArrayAccess)) {
                return false;
            }
            ArrayAccess other = (ArrayAccess) obj;
            return receiver.equals(other.receiver) && index.equals(other.index);
        }

        @Override
        public int hashCode() {
            return Objects.hash(receiver, index);
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder();
            result.append(receiver.toString());
            result.append("[");
            result.append(index.toString());
            result.append("]");
            return result.toString();
        }
    }

    /** FlowExpression for array creations. {@code new String[]()}. */
    public static class ArrayCreation extends Receiver {

        /**
         * List of dimensions expressions. {code null} means that there is no dimension expression.
         */
        protected final List<? extends @Nullable Receiver> dimensions;
        /** List of initializers. */
        protected final List<Receiver> initializers;

        /**
         * Creates an ArrayCreation object.
         *
         * @param type array type
         * @param dimensions list of dimension expressions; {code null} means that there is no
         *     dimension expression
         * @param initializers list of initializer expressions
         */
        public ArrayCreation(
                TypeMirror type,
                List<? extends @Nullable Receiver> dimensions,
                List<Receiver> initializers) {
            super(type);
            this.dimensions = dimensions;
            this.initializers = initializers;
        }

        /**
         * Returns a list of receivers representing the dimension of this array creation.
         *
         * @return a list of receivers representing the dimension of this array creation
         */
        public List<? extends @Nullable Receiver> getDimensions() {
            return dimensions;
        }

        public List<Receiver> getInitializers() {
            return initializers;
        }

        @Override
        public boolean containsOfClass(Class<? extends FlowExpressions.Receiver> clazz) {
            for (Receiver n : dimensions) {
                if (n != null && n.getClass() == clazz) {
                    return true;
                }
            }
            for (Receiver n : initializers) {
                if (n.getClass() == clazz) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isUnassignableByOtherCode() {
            return false;
        }

        @Override
        public boolean isUnmodifiableByOtherCode() {
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimensions, initializers, getType().toString());
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof ArrayCreation)) {
                return false;
            }
            ArrayCreation other = (ArrayCreation) obj;
            return this.dimensions.equals(other.getDimensions())
                    && this.initializers.equals(other.getInitializers())
                    // It might be better to use Types.isSameType(getType(), other.getType()), but I
                    // don't have a Types object.
                    && getType().toString().equals(other.getType().toString());
        }

        @Override
        public boolean syntacticEquals(Receiver other) {
            return this.equals(other);
        }

        @Override
        public boolean containsSyntacticEqualReceiver(Receiver other) {
            return syntacticEquals(other);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("new " + type);
            if (!dimensions.isEmpty()) {
                for (Receiver dim : dimensions) {
                    sb.append("[");
                    sb.append(dim == null ? "" : dim);
                    sb.append("]");
                }
            }
            if (!initializers.isEmpty()) {
                boolean needComma = false;
                sb.append(" {");
                for (Receiver init : initializers) {
                    if (needComma) {
                        sb.append(", ");
                    }
                    sb.append(init);
                    needComma = true;
                }
                sb.append("}");
            }
            return sb.toString();
        }
    }
}
