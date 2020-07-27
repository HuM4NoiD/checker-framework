package org.checkerframework.dataflow.analysis;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;

/** Utility class that contains all static methods that are not provided by JavaParser */
public class ReceiverUtils {

    public static boolean containsOfClass(Expression target, Class<? extends Expression> clazz) {
        if (target.isLiteralExpr()) {
            return target.getClass() == clazz;
        } else if (target.isClassExpr()) {
            return target.getClass() == clazz;
        } else if (target.isThisExpr()) {
            return target.getClass() == clazz;
        } else if (target.isFieldAccessExpr()) {
            FieldAccessExpr expr = (FieldAccessExpr) target;
            return target.getClass() == clazz || containsOfClass(expr.getScope(), clazz);
        }
        return false;
    }

    public static boolean isUnassignableByOtherCode(Expression target) {
        if (target.isLiteralExpr()) {
            return true;
        } else if (target.isClassExpr()) {
            return true;
        } else if (target.isThisExpr()) {
            return true;
        } else if (target.isFieldAccessExpr()) {
            FieldAccessExpr expr = (FieldAccessExpr) target;
            // TODO: find if the field is final or not
            return isUnassignableByOtherCode(expr.getScope());
        }
        return false;
    }

    public static boolean isUnmodifiableByOtherCode(Expression target) {
        if (target.isLiteralExpr()) {
            return true;
        } else if (target.isClassExpr()) {
            return true;
        } else if (target.isThisExpr()) {
            // TODO: get TypeMirror for checking the condition:
            // TypesUtils.isImmutableTypeInJdk(typemirror)
            return true;
        } else if (target.isFieldAccessExpr()) {
            // TODO: TypeUtils.isImmutableTypeInJdk(typemirror)
            return isUnassignableByOtherCode(target);
        }
        return false;
    }

    public static boolean syntacticEquals(Expression target, Expression other) {
        if (target.isLiteralExpr()) {
            return target.equals(other);
        } else if (target.isClassExpr()) {
            return target.equals(other);
        } else if (target.isThisExpr()) {
            return other.isThisExpr();
        } else if (target.isFieldAccessExpr()) {
            if (!other.isFieldAccessExpr()) {
                return false;
            }
            FieldAccessExpr t = (FieldAccessExpr) target, o = (FieldAccessExpr) other;
            return syntacticEquals(target, other)
                    || t.getName().equals(o.getName())
                    || syntacticEquals(t.getScope(), o.getScope());
        }
        return target == other;
    }

    public static boolean containsSyntacticEqualReceiver(Expression target, Expression other) {
        return target == other;
    }

    public static boolean containsModifiableAliasOf(
            Expression target, Store<?> store, Expression other) {
        if (target.isLiteralExpr()) {
            return false;
        } else if (target.isClassExpr()) {
            return false;
        } else if (target.isThisExpr()) {
            return false;
        } else if (target.isFieldAccessExpr()) {
            return containsModifiableAliasOf(((FieldAccessExpr) target).getScope(), store, other);
        }
        return true;
    }
}
