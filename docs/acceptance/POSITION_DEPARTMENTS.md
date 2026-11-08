# 岗位正式关联部门（2026-11-08）

## 功能与边界

新建岗位通过公司部门下拉选择已启用部门（选填）；没有可用部门时提示联系管理员。HR和公司管理员均可为自己创建的岗位选择部门。部门管理权限仍仅属于公司管理员。

岗位详情提供“关联部门 / 更换部门”，展示当前文字后由用户核对选择，不自动按名称合并历史岗位。已关联部门改名后列表/详情显示新名称；停用后保留关联并标记状态，可更换为其他启用部门。没有解除关联或批量自动迁移功能。

新增 nullable company_id、department_id；user_id继续代表原创建人和访问边界。新岗位（含JD导入）记录当前公司的company_id，旧岗位只有显式关联时补齐company_id和department_id。本阶段没有公司内招聘数据共享、面试官分配或候选人登录。

后端从当前账号成员关系确定公司，不信任请求companyId或部门名称。新增/更换时锁定并验证公司部门，禁止跨公司或停用部门；与部门停用、改名的数据库更新串行。岗位绑定先锁岗位并校验创建人，只更新部门相关字段，不覆盖岗位要求。部门名称以保存时的department文字留存，读取关联部门时显示目录中的当前名称。

## API兼容

- POST /api/v1/recruitment/positions 接受可选departmentId；传ID时以目录名称为准。省略ID仍允许旧客户端/JD部门文字及未指定部门，避免破坏导入。
- PUT /api/v1/recruitment/positions/{id} 接受departmentId；省略/null保持已有正式关联，不通过department文字覆盖关联。对已停用的原部门保留关联并允许其他岗位字段更新；新选择必须启用。
- PUT /api/v1/recruitment/positions/{id}/department：请求 {"departmentId": 数字}，必须选择启用部门。绑定他人岗位返回404，匿名访问401。
- 岗位响应新增companyId、departmentId、departmentStatus；未关联时状态为空，不可用关联使用UNAVAILABLE。department仍供原列表/搜索使用，关联后展示当前目录名称。
- JD抽取仍只接收文本字段，不采用模型返回的departmentId/companyId，也不自动匹配同名部门。

## 数据库与运行

旧MySQL先备份，依次确保公司基础、部门目录升级完成，再执行 deploy/migrations/20261108_position_departments.sql。脚本按information_schema判断列，可重复执行；新库两份初始化SQL已同步。不要把MySQL专用升级脚本用于H2；H2测试使用初始化schema。

deploy/migrations/review_position_departments.sql只读输出旧岗位、创建人、成员公司和候选部门，名称候选仅用于人工核对，不是获准映射。成员关系缺失或无匹配时明确标记，不能直接据此批量UPDATE。可在岗位详情逐项确认关联；无需外部服务和新增密钥。

本地真实MySQL已经备份并连续升级两次；岗位所有原字段、公司资料逐字节比对一致，81份简历数量保持，21个历史岗位仍未自动关联。已在忽略目录保存只读核对清单；未向真实招聘数据写入测试岗位或测试部门。更新运行服务前检查无进行中招聘任务，不调用付费模型。

## 验证与手工验收

新增PositionDepartmentTest覆盖公司/名称来源、可选部门、跨公司/停用/不存在部门、旧岗位显式绑定、改名停用后的读取与更新、HR隔离、非法绑定保留原文、JD模型ID不可信，以及HTTP认证和创建人权限。前端fixture脚本仅使用模拟API，不代表真实浏览器写库：

- scripts/verify-position-form-ui.py：正式部门ID、城市自定义及薪资上下限回归。
- scripts/verify-position-department-ui.py：旧岗位绑定、加载失败重试、提交错误保留原值、启停显示、空目录校验及手机弹窗。

手工验收：管理员在“公司管理 → 部门管理”新建启用部门，然后在岗位工作台创建岗位并选择该部门；打开历史岗位核对原文字后关联部门。停用部门后，旧岗位可查看且显示停用提示，新岗位不能选择该部门；HR只能绑定自己的岗位，其他成员的岗位和简历仍不可访问。

最终验证：mvn verify全量113项，108通过、5跳过，无失败或错误（包含本阶段9项）。前端构建、两个Chrome模拟接口脚本通过。更新后的本地服务GET岗位列表/详情及部门选项通过，确认21个旧岗位的companyId/departmentId仍为空，OpenAPI已登记绑定接口；前端5173返回200。真实写入权限链在H2集成/MockMvc中验证，未对本地真实招聘数据执行测试写入。
