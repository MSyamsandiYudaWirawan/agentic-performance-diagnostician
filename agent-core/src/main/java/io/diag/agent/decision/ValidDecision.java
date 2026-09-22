package io.diag.agent.decision;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DecisionValidator.ChangeKindValidator.class)
public @interface ValidDecision {
    String message() default "invalid decision";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
