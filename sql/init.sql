-- =====================================================================
--  个性化备考学习 Agent 系统 · 数据库初始化脚本
--  数据库：study_agent    字符集：utf8mb4    排序规则：utf8mb4_general_ci
--  引擎：InnoDB           MySQL：8.0.37
--
--  执行方式：
--    mysql -uroot -p123456 < sql/init.sql
--
--  设计原则：
--    1) 所有表统一使用 bigint 主键 + 自增，避免分库分表时的类型不一致
--    2) 所有业务表带 create_time / update_time，便于排查与审计
--    3) 需要"删除"能力的表使用逻辑删除（deleted 字段），MyBatis-Plus 全局配置
--    4) 所有高频查询条件提前建联合索引，索引列顺序遵循"等值在前、范围在后"
--    5) 本脚本幂等：可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE）
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `study_agent`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE `study_agent`;

-- ---------------------------------------------------------------------
-- 1. sys_user  用户表
--    支撑账号密码登录与微信小程序登录两条链路；
--    password 存 BCrypt 密文（$2a$10$... 定长 60），openid/unionid 来自 code2session。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`    varchar(64)  NOT NULL                COMMENT '登录账号（唯一）',
  `password`    varchar(100) DEFAULT NULL            COMMENT 'BCrypt 密码密文，小程序用户可为空',
  `nickname`    varchar(64)  DEFAULT NULL            COMMENT '昵称',
  `avatar`      varchar(512) DEFAULT NULL            COMMENT '头像 URL',
  `phone`       varchar(20)  DEFAULT NULL            COMMENT '手机号',
  `openid`      varchar(64)  DEFAULT NULL            COMMENT '微信小程序 openid',
  `unionid`     varchar(64)  DEFAULT NULL            COMMENT '微信开放平台 unionid',
  `exam_type`   varchar(16)  DEFAULT NULL            COMMENT '备考类型：考研/考公',
  `status`      tinyint      NOT NULL DEFAULT 1      COMMENT '状态：1正常 0禁用',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_openid` (`openid`),
  KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 2. study_plan  学习计划表
--    source 区分人工创建与 Agent 生成，便于第 3 周对比两种链路。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `study_plan`;
CREATE TABLE `study_plan` (
  `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       bigint       NOT NULL                COMMENT '所属用户',
  `title`         varchar(128) NOT NULL                COMMENT '计划标题，如"3个月考研英语二"',
  `exam_type`     varchar(16)  NOT NULL                COMMENT '考试类型：考研/考公',
  `target_date`   date         NOT NULL                COMMENT '目标考试日期',
  `start_date`    date         DEFAULT NULL            COMMENT '计划开始日期',
  `daily_minutes` int          NOT NULL DEFAULT 120    COMMENT '每日可投入时长（分钟）',
  `status`        varchar(16)  NOT NULL DEFAULT '进行中' COMMENT '状态：进行中/已完成/已放弃',
  `source`        varchar(16)  NOT NULL DEFAULT '手动'  COMMENT '来源：手动/Agent生成',
  `deleted`       tinyint      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删 1已删',
  `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_target_date` (`target_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学习计划表';

-- ---------------------------------------------------------------------
-- 3. plan_task  计划任务表（逐日粒度，计划页与进度统计的最小单元）
--    idx_plan_date 支撑"取某天任务"与"按计划+日期区间聚合"。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `plan_task`;
CREATE TABLE `plan_task` (
  `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `plan_id`           bigint       NOT NULL                COMMENT '所属计划',
  `user_id`           bigint       NOT NULL                COMMENT '冗余用户ID，避免统计时回表 join',
  `task_date`         date         NOT NULL                COMMENT '任务日期',
  `title`             varchar(128) NOT NULL                COMMENT '任务标题',
  `content`           varchar(1000) DEFAULT NULL           COMMENT '任务详情/要求',
  `subject`           varchar(32)  DEFAULT NULL            COMMENT '科目：数学/英语/政治/专业课…',
  `estimated_minutes` int          NOT NULL DEFAULT 60     COMMENT '预计时长（分钟）',
  `status`            varchar(16)  NOT NULL DEFAULT '待办'  COMMENT '状态：待办/完成/跳过',
  `sort`              int          NOT NULL DEFAULT 0      COMMENT '同日排序',
  `deleted`           tinyint      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `finish_time`       datetime     DEFAULT NULL            COMMENT '完成时间',
  `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_plan_date` (`plan_id`, `task_date`),
  KEY `idx_user_date` (`user_id`, `task_date`),
  KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '计划任务表';

-- ---------------------------------------------------------------------
-- 4. wrong_question  错题本
--    索引 idx_user_subject_mastery 是第 5 周 SQL 优化的重点验证对象。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `wrong_question`;
CREATE TABLE `wrong_question` (
  `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       bigint       NOT NULL                COMMENT '所属用户',
  `subject`       varchar(32)  NOT NULL                COMMENT '科目',
  `question_type` varchar(32)  DEFAULT NULL            COMMENT '题型：单选/多选/填空/解答/论述…',
  `content`       text         NOT NULL                COMMENT '题干',
  `answer`        text         DEFAULT NULL            COMMENT '正确答案',
  `analysis`      text         DEFAULT NULL            COMMENT '解析',
  `image_url`     varchar(512) DEFAULT NULL            COMMENT '题目图片 URL',
  `difficulty`    tinyint      NOT NULL DEFAULT 3      COMMENT '难度 1~5',
  `mastery`       varchar(16)  NOT NULL DEFAULT '未掌握' COMMENT '掌握度：未掌握/复习中/已掌握',
  `source`        varchar(64)  DEFAULT NULL            COMMENT '来源：真题/模拟题/教材…',
  `deleted`       tinyint      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_subject_mastery` (`user_id`, `subject`, `mastery`),
  KEY `idx_user_create` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '错题表';

-- ---------------------------------------------------------------------
-- 5-1. wrong_question_tag  错题标签表（AI 归类写这里）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `wrong_question_tag`;
CREATE TABLE `wrong_question_tag` (
  `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint      NOT NULL                COMMENT '所属用户（标签可私人定制）',
  `name`        varchar(64) NOT NULL                COMMENT '标签名，如"定积分"',
  `category`    varchar(32) DEFAULT NULL            COMMENT '标签分类，如"数学/定积分"的知识点分类',
  `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_name` (`user_id`, `name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '错题标签表';

-- ---------------------------------------------------------------------
-- 5-2. wrong_question_tag_rel  错题-标签关联表
--      唯一键兼作消费幂等键：RocketMQ 重复投递时 INSERT IGNORE / ON DUPLICATE KEY 天然去重。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `wrong_question_tag_rel`;
CREATE TABLE `wrong_question_tag_rel` (
  `id`                bigint   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `wrong_question_id` bigint   NOT NULL                COMMENT '错题ID',
  `tag_id`            bigint   NOT NULL                COMMENT '标签ID',
  `create_time`       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_tag` (`wrong_question_id`, `tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '错题标签关联表';

-- ---------------------------------------------------------------------
-- 6. study_record  学习记录表
--    进度追踪的数据源：完成 plan_task 时写一条，统计接口 group by study_date。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `study_record`;
CREATE TABLE `study_record` (
  `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      bigint       NOT NULL                COMMENT '用户ID',
  `plan_task_id` bigint       DEFAULT NULL            COMMENT '关联任务ID，手动计时可为空',
  `study_date`   date         NOT NULL                COMMENT '学习日期（统计维度）',
  `minutes`      int          NOT NULL DEFAULT 0      COMMENT '学习时长（分钟）',
  `subject`      varchar(32)  DEFAULT NULL            COMMENT '科目',
  `note`         varchar(500) DEFAULT NULL            COMMENT '备注',
  `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_date` (`user_id`, `study_date`),
  KEY `idx_task` (`plan_task_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学习记录表';

-- ---------------------------------------------------------------------
-- 7. review_report  复盘报告表
--    content 存 markdown 文本，stats_json 存给 ECharts 用的数据卡片 JSON。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `review_report`;
CREATE TABLE `review_report` (
  `id`           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      bigint      NOT NULL                COMMENT '用户ID',
  `report_type`  varchar(16) NOT NULL DEFAULT '周报'  COMMENT '类型：周报/月报',
  `period_start` date        NOT NULL                COMMENT '统计周期开始',
  `period_end`   date        NOT NULL                COMMENT '统计周期结束',
  `content`      mediumtext  DEFAULT NULL            COMMENT 'markdown 正文',
  `stats_json`   json        DEFAULT NULL            COMMENT '统计数据 JSON（完成率/时长/错题趋势）',
  `create_time`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_period` (`user_id`, `period_start`, `period_end`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '复盘报告表';

-- ---------------------------------------------------------------------
-- 8. notice_message  站内通知表
--    与微信订阅消息互为补充：PC 端读这张表，小程序走 wx-push。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `notice_message`;
CREATE TABLE `notice_message` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint       NOT NULL                COMMENT '接收用户',
  `type`        varchar(16)  NOT NULL DEFAULT '提醒'  COMMENT '类型：提醒/复习/报告',
  `title`       varchar(128) NOT NULL                COMMENT '标题',
  `content`     varchar(1000) DEFAULT NULL           COMMENT '内容',
  `channel`     varchar(16)  NOT NULL DEFAULT 'PC'   COMMENT '渠道：PC/小程序',
  `read_flag`   tinyint      NOT NULL DEFAULT 0      COMMENT '已读：0未读 1已读',
  `biz_id`      bigint       DEFAULT NULL            COMMENT '业务ID，如报告ID，用于跳转',
  `send_time`   datetime     DEFAULT NULL            COMMENT '发送时间',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`, `read_flag`),
  KEY `idx_user_send` (`user_id`, `send_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '站内通知表';

-- ---------------------------------------------------------------------
-- 9. kb_document  知识库文档表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `kb_document`;
CREATE TABLE `kb_document` (
  `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      bigint       NOT NULL                COMMENT '所属用户',
  `file_name`    varchar(255) NOT NULL                COMMENT '原始文件名',
  `file_path`    varchar(512) NOT NULL                COMMENT '本地存储路径',
  `file_type`    varchar(16)  DEFAULT NULL            COMMENT '文件类型：pdf/docx/txt',
  `file_size`    bigint       DEFAULT NULL            COMMENT '文件大小（字节）',
  `parse_status` varchar(16)  NOT NULL DEFAULT '待解析' COMMENT '状态：待解析/解析中/完成/失败',
  `chunk_count`  int          NOT NULL DEFAULT 0      COMMENT '切分出的片段数',
  `error_msg`    varchar(500) DEFAULT NULL            COMMENT '解析失败原因',
  `upload_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `update_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`, `parse_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识库文档表';

-- ---------------------------------------------------------------------
-- 10. kb_chunk  文档切片表
--     vector_id 对应 Milvus collection(kb_chunk) 的主键，做双向定位。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `kb_chunk`;
CREATE TABLE `kb_chunk` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `document_id` bigint       NOT NULL                COMMENT '所属文档',
  `user_id`     bigint       NOT NULL                COMMENT '冗余用户ID，用于向量检索时按用户过滤',
  `chunk_index` int          NOT NULL                COMMENT '片段序号，从 0 开始',
  `content`     text         NOT NULL                COMMENT '片段文本',
  `page_no`     int          DEFAULT NULL            COMMENT '来源页码，用于引用溯源',
  `vector_id`   varchar(64)  DEFAULT NULL            COMMENT 'Milvus 主键',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_document_chunk` (`document_id`, `chunk_index`),
  KEY `idx_vector` (`vector_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识库文档切片表';

-- ---------------------------------------------------------------------
-- 11-1. chat_session  问答会话表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       bigint       NOT NULL                COMMENT '所属用户',
  `title`         varchar(128) NOT NULL DEFAULT '新会话' COMMENT '会话标题（取首问前 30 字）',
  `message_count` int          NOT NULL DEFAULT 0      COMMENT '消息条数',
  `deleted`       tinyint      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `last_time`     datetime     DEFAULT NULL            COMMENT '最后一条消息时间',
  `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_last` (`user_id`, `last_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问答会话表';

-- ---------------------------------------------------------------------
-- 11-2. chat_message  问答消息表
--      citations 存引用溯源 JSON：[{documentId,documentName,chunkId,chunkIndex,pageNo,score,snippet}]
--      reasoning_content 单独留字段存 qwen3.7-flash 的思考过程。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id`                bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id`        bigint      NOT NULL                COMMENT '所属会话',
  `user_id`           bigint      NOT NULL                COMMENT '冗余用户ID',
  `role`              varchar(16) NOT NULL                COMMENT '角色：user/assistant/system',
  `content`           mediumtext  DEFAULT NULL            COMMENT '消息正文',
  `reasoning_content` mediumtext  DEFAULT NULL            COMMENT '思考过程（reasoning_content）',
  `citations`         json        DEFAULT NULL            COMMENT '引用溯源 JSON 数组',
  `token_count`       int         NOT NULL DEFAULT 0      COMMENT 'token 消耗',
  `create_time`       datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`, `id`),
  KEY `idx_user_create` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问答消息表';

-- ---------------------------------------------------------------------
-- 初始化数据：默认演示账号
--   密码明文：123456
--   BCrypt 密文：$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2
--   说明：该密文由 BCryptPasswordEncoder(strength=10) 生成，登录时由 Spring Security 校验。
-- ---------------------------------------------------------------------
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `exam_type`, `status`)
SELECT 'demo', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '演示同学', '考研', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_user` WHERE `username` = 'demo');

SET FOREIGN_KEY_CHECKS = 1;
