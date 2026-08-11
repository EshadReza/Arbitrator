package com.arbitrator.common.dto;

/**
 * One clarification: a contestant's question and, once given, the answer.
 *
 * A public, answered clarification reaches the rest of the class only once an
 * admin has approved it (see {@code approved} below) — a question being
 * marked public at ask-time is the asker's request, not a publish decision.
 *
 * {@code askedBy} is the single asymmetry: the instructor needs to know who is
 * asking, contestants must not. It is filled for admin callers and left null
 * for everyone else, so a participant response simply does not carry the name
 * rather than carrying it and trusting the UI to hide it.
 */
public record ClarificationDto(
        long id,
        String problemCode,      // null when the question is about the contest itself
        String problemTitle,
        String question,
        String answer,           // null until answered
        long askedAtMs,
        long answeredAtMs,       // -1 until answered
        String askedBy,          // admin view only; null for participants
        /**
         * The asker's own choice. A private clarification is filtered out of
         * this list entirely for everyone except the asker and the instructor
         * — this flag is carried so the UI can show a "Private" badge, not as
         * the mechanism that hides it (the server never sends it to anyone
         * not entitled to see it in the first place).
         */
        boolean isPublic,
        /**
         * An admin's explicit sign-off that a public Q&A may be shown to
         * everyone. Irrelevant for a private clarification. Like {@code
         * isPublic}, this rides along for the UI (e.g. "awaiting approval")
         * rather than being the enforcement mechanism — an unapproved public
         * clarification is never sent to anyone but its asker and admins.
         */
        boolean approved
) {

    public boolean answered() {
        return answer != null && !answer.isBlank();
    }
}
