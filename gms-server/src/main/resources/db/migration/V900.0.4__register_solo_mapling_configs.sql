-- SoloMapling bot framework runtime configuration (game_config).
-- Mirrors the code defaults in BotGeneration / EnvironmentManager / BotBootstrap
-- so admins can tune them from BeiDou's config UI without a rebuild.
--
--   bot_template_cid     : character id every bot is cloned from (V900.0.2 provisions it)
--   bot_population_scale : multiplier on the two large-scale spawn waves (wave 8 training
--                          cohorts + wave 9 town presence, ~2500 bots at full density).
--                          0.35 lands the whole-world population near 1000 bots;
--                          1.0 restores full SoloMapling density.
--   spawn_bots_on_startup: master switch for the cold-boot bot choreography

INSERT INTO game_config (config_type, config_sub_type, config_clazz, config_code, config_value, config_desc)
VALUES ('server', 'SoloMapling', 'java.lang.Integer', 'bot_template_cid', '990000001',
        '机器人克隆模板角色ID，须与characters表中V900.0.2创建的模板一致(Character id every bot is cloned from, provisioned by migration V900.0.2)'),
       ('server', 'SoloMapling', 'java.lang.Double', 'bot_population_scale', '0.35',
        '机器人口数倍率，作用于练级机器人和城镇人口两大波次，0.35约1000个，1.0为完整密度(Population multiplier on training/town spawn waves, 0.35 ~ 1000 bots, 1.0 full density)'),
       ('server', 'SoloMapling', 'java.lang.Boolean', 'spawn_bots_on_startup', 'true',
        '服务器启动时是否自动生成机器人环境(Spawn the bot environment on server startup)');
