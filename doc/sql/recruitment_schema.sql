-- ============================================================
-- 智能招聘助手 业务表 (M1)
-- 依赖: 现有 document / document_chunk / user / multi_agent_task 表
-- ============================================================

-- 1. 岗位表: 一个HR可发布多个岗位
CREATE TABLE IF NOT EXISTS job_position (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id          BIGINT NOT NULL COMMENT '发布HR用户ID',
  title            VARCHAR(200) NOT NULL COMMENT '岗位名称',
  department       VARCHAR(100) COMMENT '部门',
  city             VARCHAR(50) COMMENT '工作城市',
  salary_min       INT COMMENT '薪资下限(k)',
  salary_max       INT COMMENT '薪资上限(k)',
  required_skills  TEXT COMMENT '必须技能JSON数组',
  preferred_skills TEXT COMMENT '加分技能JSON数组',
  min_years        INT DEFAULT 0 COMMENT '最低工作年限',
  education        VARCHAR(20) COMMENT '学历要求',
  description      TEXT COMMENT 'JD全文',
  doc_id           VARCHAR(64) COMMENT '关联上传的JD文档ID(document.document_id)',
  status           VARCHAR(20) DEFAULT 'OPEN' COMMENT 'DRAFT/OPEN/MATCHING/CLOSED',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_position_user (user_id),
  INDEX idx_position_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='招聘岗位';

-- 2. 候选人简历表
CREATE TABLE IF NOT EXISTS candidate_resume (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_id     BIGINT NOT NULL COMMENT '应聘岗位',
  user_id         BIGINT NOT NULL COMMENT '上传HR(冗余,权限过滤)',
  candidate_name  VARCHAR(100) COMMENT '候选人姓名',
  phone           VARCHAR(30) COMMENT '联系方式(脱敏存储)',
  email           VARCHAR(100),
  skills          TEXT COMMENT '技能标签JSON数组',
  years_exp       INT COMMENT '工作年限',
  education       VARCHAR(50) COMMENT '最高学历',
  last_company    VARCHAR(200) COMMENT '最近公司',
  raw_text        MEDIUMTEXT COMMENT '简历全文(供LLM分析)',
  doc_id          VARCHAR(64) COMMENT '关联document.document_id(复用解析/分块/向量化)',
  parse_status    VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PARSED/FAILED',
  parse_error     VARCHAR(500) COMMENT '解析失败原因',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_resume_position (position_id),
  INDEX idx_resume_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选人简历';

-- 3. 人岗匹配结果表
CREATE TABLE IF NOT EXISTS match_result (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_id     BIGINT NOT NULL,
  resume_id       BIGINT NOT NULL,
  match_score     DECIMAL(5,2) COMMENT '综合匹配分0-100',
  skill_hits      TEXT COMMENT '命中技能JSON',
  skill_gaps      TEXT COMMENT '缺失技能JSON',
  score_detail    TEXT COMMENT '评分明细JSON(经验/技能/学历分项)',
  hr_decision     VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  rank_no         INT COMMENT '排名',
  task_id         BIGINT COMMENT '产生该结果的Agent任务ID',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_match_position (position_id),
  INDEX idx_match_resume (resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人岗匹配结果';

-- 4. 定制面试题表
CREATE TABLE IF NOT EXISTS interview_question (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id        BIGINT NOT NULL,
  position_id      BIGINT NOT NULL,
  category         VARCHAR(50) COMMENT '技术/项目/行为',
  question         TEXT COMMENT '题目',
  assess_point     VARCHAR(500) COMMENT '考察点',
  reference_answer TEXT COMMENT '参考答案要点',
  difficulty       VARCHAR(10) COMMENT 'EASY/MEDIUM/HARD',
  hr_decision      VARCHAR(20) DEFAULT 'PENDING',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_question_resume (resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定制面试题';

-- 5. 候选人评估报告表
CREATE TABLE IF NOT EXISTS candidate_report (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id        BIGINT NOT NULL,
  position_id      BIGINT NOT NULL,
  summary          TEXT COMMENT '一句话总评',
  strength         TEXT COMMENT '优势',
  risk             TEXT COMMENT '风险提示',
  salary_suggest   VARCHAR(100) COMMENT '建议薪资区间',
  market_ref       TEXT COMMENT '市场行情引用(来源URL JSON)',
  final_recommend  VARCHAR(20) COMMENT 'STRONG_YES/YES/NO',
  report_md        MEDIUMTEXT COMMENT '完整报告Markdown',
  hr_decision      VARCHAR(20) DEFAULT 'PENDING',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_report_resume (resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选人评估报告';
