package org.checkerframework.dataflow.analysis;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.ThisExpr;
import java.util.Optional;

/** Utility class that contains all static methods that are not provided by JavaParser */
public class ReceiverUtils {

    public static boolean containsOfClass(ThisExpr target, Class<? extends Expression> clazz) {
        return target.getClass() == clazz;
    }

    public static boolean isUnassignableByOtherCode(ThisExpr target) {
        Optional<Name> optionalName = target.getTypeName();
        if (optionalName.isPresent()) {
            return true;
        }
        return false;
    }

    public static boolean containsOfClass(Expression target, Class<? extends Expression> clazz) {
        return false;
    }

    public static boolean isUnassignableByOtherCode(Expression target) {
        return false;
    }

    public static boolean isUnmodifiableByOtherCode(Expression target) {
        return false;
    }

    public static boolean syntacticEquals(Expression target, Expression other) {
        return target == other;
    }

    public static boolean containsSyntacticEqualReceiver(Expression target, Expression other) {
        return target == other;
    }

    public static boolean containsModifiableAliasOf(
            Expression target, Store<?> store, Expression other) {
        return true;
    }
}
