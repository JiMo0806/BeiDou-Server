-- SoloMapling bot system: bot-only account + base template character.
-- Login: fmbot / password  (account is never logged into by a real player)
--
-- BotGeneration.createBot() and getConsoleBot() clone this template character
-- for every spawned bot (see BotGeneration.getBotTemplateCid(), default
-- 990000001, overridable via game_config: server/bot_template_cid).
--
-- Original SoloMapling hardcoded CID 2 as the template; on production
-- databases that ID is almost certainly taken by a real character, so the
-- template now lives at a safe high ID far above the AUTO_INCREMENT range.
--
-- The template must stay a plain character: gm = 0 (NOT an admin), level 1,
-- and no equipped items, since bots are decorated after cloning.
--
-- INSERT IGNORE: on databases that already have CID 990000001 (or an 'fmbot'
-- account) from manual setup, these rows are skipped instead of failing
-- the migration.

INSERT IGNORE INTO accounts (`name`, password, pin, pic, birthday, nxCredit, maplePoint, nxPrepaid, characterslots,
                             gender, tos)
VALUES ('fmbot', '$2y$12$xS3xZTX5hSU8v0SvC4h1FewFeK4Lx0q6kXoqv/bFJu6Hr3Wuimr9q', '0000', '000000',
        '2005-05-11', 0, 0, 0, 3, 0, 1);

INSERT IGNORE INTO characters (id, accountid, world, `name`, level, exp,
                               str, dex, luk, `int`, hp, mp, maxhp, maxmp, meso, job, skincolor, gender,
                               hair, face, ap, map, spawnpoint, gm, equipslots, useslots,
                               setupslots, etcslots)
VALUES (990000001, (SELECT id FROM accounts WHERE `name` = 'fmbot'), 0, 'fmbot', 1, 0,
        12, 5, 4, 4, 50, 5, 50, 5, 0, 0, 0, 0,
        30030, 20000, 0, 10000, 0, 0, 96, 96,
        96, 96);
