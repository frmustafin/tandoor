-- Starting assortment from section 3 of the plan. Descriptions, prices and photos are
-- left empty on purpose: the bakery has not supplied them yet (open question 5), and the
-- admin fills them in from the bot without a migration.
INSERT INTO products (name, sort_order, updated_at) VALUES
    ('Самса из печи',    1, CURRENT_TIMESTAMP),
    ('Самса из тандыра', 2, CURRENT_TIMESTAMP),
    ('Лепёшка с сыром',  3, CURRENT_TIMESTAMP),
    ('Лепёшка с мясом',  4, CURRENT_TIMESTAMP),
    ('Обычная лепёшка',  5, CURRENT_TIMESTAMP);

-- Defaults for the two timers and the channel auto-post switch, all editable by the admin.
INSERT INTO settings (setting_key, setting_value, updated_at) VALUES
    ('ready_after_minutes', '15',   CURRENT_TIMESTAMP),
    ('hot_for_minutes',     '30',   CURRENT_TIMESTAMP),
    ('channel_auto_posts',  'true', CURRENT_TIMESTAMP);
