-- SoloMapling bot framework: register GM commands (level 4)
-- Commands live in the soloMapling.commands package (fully-qualified clazz).
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('bot', 4, 1, 'soloMapling.commands.ArtificialPlayerCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('opq', 4, 1, 'soloMapling.commands.OPQCommands', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('reactor', 4, 1, 'soloMapling.commands.ReactorCommands', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('move', 4, 1, 'soloMapling.commands.BotMoveCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('gcmove', 4, 1, 'soloMapling.commands.GCMoveCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('betafmshop', 4, 1, 'soloMapling.commands.ArtificialFreeMarketCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('test', 4, 1, 'soloMapling.commands.TestDevCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('fmbot', 4, 1, 'soloMapling.commands.FMBotCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('tradebot', 4, 1, 'soloMapling.commands.TradeBotTestCommand', 4);
INSERT INTO command_info (syntax, level, enabled, clazz, default_level) VALUES ('env', 4, 1, 'soloMapling.commands.EnvironmentCommand', 4);
