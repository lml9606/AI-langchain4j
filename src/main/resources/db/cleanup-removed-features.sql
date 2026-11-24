-- 仅用于清理已删除功能在存量库中的历史表；不会由应用自动执行。
-- 执行前请确认不再需要这些历史数据，并按需先备份。
DROP TABLE IF EXISTS ai_eval_result;
DROP TABLE IF EXISTS ai_eval_case;
DROP TABLE IF EXISTS ai_feedback;
DROP TABLE IF EXISTS ai_resume_revision;
DROP TABLE IF EXISTS ai_user_resume;
DROP TABLE IF EXISTS ai_demo_refund;
DROP TABLE IF EXISTS ai_demo_order;
DROP TABLE IF EXISTS ai_task_step;
DROP TABLE IF EXISTS ai_task_run;
DROP TABLE IF EXISTS ai_graph_execution;
ALTER TABLE ai_tool_execution DROP COLUMN IF EXISTS plan_json;
