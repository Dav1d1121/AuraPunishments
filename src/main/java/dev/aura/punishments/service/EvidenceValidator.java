package dev.aura.punishments.service;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.model.Simple.EvidenceType;
import dev.aura.punishments.util.Result;
import dev.aura.punishments.util.Result.Code;
import dev.aura.punishments.util.Text;

/** Validates and cleans evidence references. Links must be http(s); written reports need substance. */
public final class EvidenceValidator {
    private EvidenceValidator() {}

    public record Input(EvidenceType type, String reference, String description) {}

    public static Result<Input> validate(Settings s, Input in) {
        if (in == null || in.type() == null) return Result.fail(Code.EVIDENCE, "Choose an evidence type.");
        String ref = Text.clean(in.reference());
        String desc = Text.truncate(Text.clean(in.description()), 200);
        if (ref.isEmpty()) return Result.fail(Code.EVIDENCE, "The evidence reference cannot be empty.");
        if (ref.length() > s.cases.maxEvidenceRef()) {
            return Result.fail(Code.EVIDENCE, "The evidence reference is too long (max " + s.cases.maxEvidenceRef() + " characters).");
        }
        if (in.type().isLink() && !Text.isHttpUrl(ref)) {
            return Result.fail(Code.EVIDENCE, in.type().name().toLowerCase().replace('_', ' ')
                    + " evidence must be an http(s) link.");
        }
        if (in.type() == EvidenceType.INCIDENT_REPORT && ref.length() < s.cases.minWrittenReport()) {
            return Result.fail(Code.EVIDENCE, "A written incident report must be at least " + s.cases.minWrittenReport() + " characters.");
        }
        if (ref.length() < 3) return Result.fail(Code.EVIDENCE, "The evidence reference is too short.");
        return Result.ok(new Input(in.type(), ref, desc));
    }
}
