-- Keep V10 immutable after it has been applied. Add the report dismissal action forward-only.

ALTER TABLE moderation_actions DROP CONSTRAINT ck_moderation_actions_rule_2;
ALTER TABLE moderation_actions ADD CONSTRAINT ck_moderation_actions_rule_2
    CHECK (action IN ('APPROVE', 'REJECT', 'SUSPEND', 'RESTORE', 'CLOSE', 'HIDE', 'REMOVE', 'WARN', 'DISMISS'));
