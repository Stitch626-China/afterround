-- FR6.7 Offer 决策依据快照：当时看重什么、最终选择及原因。
ALTER TABLE offers
    ADD COLUMN decision_priorities  VARCHAR(1000),
    ADD COLUMN final_choice         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN final_choice_reason  VARCHAR(1000);

-- 全局最多一个最终选择；服务层在标记前先清除其他 Offer。
CREATE UNIQUE INDEX uk_offers_final_choice ON offers ((final_choice)) WHERE final_choice;
