package io.diag.agent.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.ToolEnvelope;
import io.diag.evidence.dto.ChangeDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parse → validate → DecisionDto or parseable rejection envelope (§10.21).
 * One-retry-then-WASTED is step-9 loop policy; this ships the validator.
 */
@Service
public class DecisionValidator {

    private final ObjectMapper mapper;
    private final Validator validator;

    public DecisionValidator(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * Returns ok(DecisionDto) on success, fail(message) on any parse or
     * validation error — always parseable JSON back to the model (§10.4).
     */
    public ToolEnvelope<DecisionDto> validate(String json) {
        DecisionDto dto;
        try {
            dto = mapper.readValue(json, DecisionDto.class);
        } catch (Exception e) {
            return ToolEnvelope.fail("Decision parse error: " + e.getMessage());
        }

        Set<ConstraintViolation<DecisionDto>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            return ToolEnvelope.fail("Decision validation error: " + msg);
        }
        return ToolEnvelope.ok(dto);
    }

    // -------------------------------------------------------------------------
    // Cross-field constraint: kind=edits → non-empty edits; kind=template → non-blank id
    // -------------------------------------------------------------------------

    public static final class ChangeKindValidator
            implements ConstraintValidator<ValidDecision, DecisionDto> {

        @Override
        public boolean isValid(DecisionDto dto, ConstraintValidatorContext ctx) {
            if (dto == null || dto.change() == null) return true; // @NotNull handles null
            ChangeDto c = dto.change();
            if ("edits".equals(c.kind())) {
                if (c.edits() == null || c.edits().isEmpty()) {
                    ctx.disableDefaultConstraintViolation();
                    ctx.buildConstraintViolationWithTemplate(
                                    "kind=edits requires a non-empty edits list")
                            .addPropertyNode("change.edits").addConstraintViolation();
                    return false;
                }
            } else if ("template".equals(c.kind())) {
                if (c.template() == null || c.template().isBlank()) {
                    ctx.disableDefaultConstraintViolation();
                    ctx.buildConstraintViolationWithTemplate(
                                    "kind=template requires a non-blank template id")
                            .addPropertyNode("change.template").addConstraintViolation();
                    return false;
                }
            } else {
                // anything else — including a missing/null kind — is rejected here,
                // at decision validation (§10.21), not later inside ChangeApplier
                ctx.disableDefaultConstraintViolation();
                ctx.buildConstraintViolationWithTemplate(
                                "change.kind must be edits or template, got: " + c.kind())
                        .addPropertyNode("change.kind").addConstraintViolation();
                return false;
            }
            return true;
        }
    }
}
