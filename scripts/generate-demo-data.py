"""Rebuild fictional recruitment fixtures. Does not read real resumes."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'demo-data'
# title, team, city, salary(min/max), years, core, optional, scope, deliverables
JOBS=[
('Java后端工程师｜订单与履约','交易研发','上海',18,28,3,['Java','Spring Boot','MySQL','Redis'],['RabbitMQ','Seata','Docker'],'订单创建、库存预占、支付回调与履约状态同步','实现幂等回调与超时关单；为库存扣减设计并发控制和补偿方案'),
('Java微服务工程师｜物流平台','物流研发','杭州',20,32,3,['Java','Spring Cloud','MySQL','RabbitMQ'],['Nacos','Seata','Redis'],'运单、运输任务和轨迹查询的接口开发','梳理跨服务状态同步；为消息重复、乱序和失败重试建立处理策略'),
('Java后端工程师｜企业ERP','企业服务','苏州',15,24,2,['Java','Spring Boot','MyBatis','MySQL'],['Redis','Vue','单元测试'],'采购入库、销售出库、库存台账与权限管理','完成库存流水和事务边界设计；提供业务对账和异常追溯接口'),
('高级Java工程师｜高并发交易','交易架构','深圳',28,42,5,['Java','Spring Boot','Redis','MySQL','JVM'],['Kafka','分库分表','性能调优'],'交易服务容量评估、慢接口排查与缓存一致性治理','通过可复现压测定位瓶颈；给出容量方案、监控指标与回滚预案'),
('Java搜索服务工程师','搜索研发','北京',22,35,4,['Java','Elasticsearch','MySQL','Spring Boot'],['Kafka','Redis','搜索评测'],'业务搜索索引、增量同步和检索接口开发','设计索引字段与同步补偿；用测试查询集验证排序和召回变化'),
('Java业务开发工程师｜初级','业务研发','成都',10,16,1,['Java','Spring Boot','MySQL'],['MyBatis','Git','单元测试'],'企业管理系统的业务接口、数据校验和联调','独立交付小型业务模块；编写接口文档和关键业务测试'),
('AI应用工程师｜企业知识库','智能应用','杭州',20,32,3,['Python','LangChain','RAG','FastAPI'],['Milvus','Qdrant','Rerank'],'文档解析、切分、向量检索和来源引用问答','建立可重复评测集；定位解析失败、检索遗漏和无依据回答'),
('Java与AI集成工程师','智能业务','上海',20,34,3,['Java','Spring Boot','RAG','MySQL'],['Python','SSE','LangChain'],'将知识问答和结构化模型输出接入现有业务系统','设计Java与AI服务边界；处理超时、权限、流式输出和失败恢复'),
('Agent应用工程师｜业务工具调用','智能流程','深圳',22,36,3,['Python','LangChain','Agent','FastAPI'],['LangGraph','工具调用','Docker'],'构建可调用业务工具的助手和结构化结果校验','限制工具权限与重试次数；覆盖工具异常、参数错误和用户取消'),
('LangGraph工作流工程师','智能流程','北京',26,40,4,['Python','LangGraph','Agent','工作流'],['SQLite','PostgreSQL','人工审核'],'实现含分支、重试和人工介入的长流程','设计检查点与状态恢复；验证重启恢复、重复审批和版本冲突'),
('Dify应用实施工程师','解决方案','武汉',13,22,2,['Dify','RAG','HTTP API'],['Python','SQL','Prompt工程'],'基于客户资料配置知识库和业务工作流','完成需求澄清、工作流配置、异常排查和交付说明'),
('AI检索工程师｜召回与评测','检索研发','上海',25,38,4,['Python','RAG','向量检索','检索评测'],['Milvus','Elasticsearch','Rerank'],'稠密与关键词混合检索、融合排序和精排评测','设计相关性标注与离线评测；解释不同策略的质量和延迟变化'),
('React前端工程师｜招聘工作台','产品研发','杭州',16,26,3,['React','TypeScript','HTML/CSS'],['Ant Design','SSE','Vite'],'岗位、候选人、审核和资料管理界面开发','改善空状态、错误反馈和键盘操作；完成表单与流式交互联调'),
('Vue全栈工程师｜企业后台','企业服务','南京',15,25,3,['Vue','Java','Spring Boot','MySQL'],['Redis','Docker','权限管理'],'企业后台的前后端功能开发与交付','完成表单校验、权限边界和分页查询；提供部署与运维说明'),
('AI产品全栈工程师','智能产品','深圳',22,36,4,['React','TypeScript','Python','FastAPI'],['LangChain','SSE','Docker'],'从对话页面到模型服务接口的产品功能交付','处理流式取消、长请求状态和错误恢复；交付可演示完整流程'),
('平台运维工程师｜容器与可观测性','平台工程','北京',20,32,3,['Linux','Docker','Kubernetes'],['Prometheus','Grafana','CI/CD'],'业务容器部署、服务监控与故障排查','维护发布回滚、备份恢复和告警规则；完成故障演练'),
('数据工程师｜招聘分析','数据平台','上海',18,30,3,['Python','SQL','数据建模'],['Airflow','Kafka','MySQL'],'招聘业务数据清洗、指标口径与统计任务','建立可追溯数据链路；实现增量处理、去重和数据质量检查'),
('模型服务工程师｜推理部署','模型平台','杭州',28,44,4,['Python','Linux','Docker','vLLM'],['Kubernetes','GPU','模型量化'],'模型推理服务部署、资源使用监控与性能测试','对比吞吐、首字延迟与显存使用；输出资源规划与故障处理步骤'),
('测试开发工程师｜业务接口','质量工程','成都',14,24,2,['Python','pytest','接口测试','SQL'],['Java','CI/CD','性能测试'],'招聘与订单系统的接口自动化、回归与异常测试','覆盖权限隔离、幂等与状态流转；维护可重复测试数据'),
('AI质量工程师｜RAG与Agent评测','智能质量','深圳',20,32,3,['Python','pytest','RAG','评测'],['LangChain','工具调用','Prompt工程'],'模型应用的测试样本、回归机制与失败归因','覆盖资料引用、提示注入、工具失败和长流程恢复等场景')]
# Fictional profiles; no real people or real employer histories.
PROFILES=[
('Java业务开发',3,['Java','Spring Boot','MyBatis','MySQL','Redis','RabbitMQ'],'订单与库存','完成订单状态、库存流水和重复请求校验'),
('Java微服务',4,['Java','Spring Cloud','MySQL','Redis','RabbitMQ','Nacos','Seata'],'运输任务平台','实现运单查询、消息重试和轨迹数据同步'),
('Java与AI集成',3,['Java','Spring Boot','MySQL','Redis','RAG','Python','SSE'],'知识问答集成','接入知识检索接口、流式响应和会话记录'),
('Java交易架构',7,['Java','Spring Boot','MySQL','Redis','JVM','Kafka','性能调优','分库分表'],'交易链路优化','设计压测、热点缓存保护与数据库查询优化'),
('Python知识库应用',3,['Python','FastAPI','LangChain','RAG','Milvus','Rerank'],'企业资料问答','实现文档切块、混合检索和来源引用'),
('Python工具调用',4,['Python','FastAPI','LangChain','Agent','工具调用','Docker'],'业务查询助手','实现受控工具参数、失败重试和结构化结果'),
('LangGraph工作流',5,['Python','LangChain','LangGraph','Agent','工作流','SQLite','人工审核'],'审核工作流','实现持久化检查点、人工反馈与恢复执行'),
('Dify交付',2,['Dify','RAG','HTTP API','Prompt工程','SQL'],'企业客服实施','配置检索工作流、交付文档和异常排查'),
('React产品开发',3,['React','TypeScript','HTML/CSS','Ant Design','Vite','SSE'],'招聘管理界面','实现候选人列表、表单校验和流式消息展示'),
('Vue企业后台',4,['Vue','Java','Spring Boot','MySQL','Redis','权限管理'],'进销存后台','完成库存台账、权限菜单和前后端联调'),
('Python全栈',4,['React','TypeScript','Python','FastAPI','Docker','SSE'],'智能产品原型','完成对话页面、文件上传和服务接口'),
('搜索服务',5,['Java','Spring Boot','MySQL','Elasticsearch','Kafka','搜索评测'],'业务搜索','维护索引同步、查询优化和排序回归'),
('容器平台',4,['Linux','Docker','Kubernetes','Prometheus','Grafana','CI/CD'],'服务发布平台','实现健康探测、灰度发布和监控告警'),
('数据工程',3,['Python','SQL','数据建模','Airflow','MySQL'],'经营分析数据管道','实现增量同步、去重与数据质量校验'),
('模型推理部署',5,['Python','Linux','Docker','vLLM','GPU','模型量化','Kubernetes'],'推理服务部署','完成吞吐测试、显存监控与容量规划'),
('接口测试开发',3,['Python','pytest','接口测试','SQL','CI/CD','性能测试'],'业务接口回归','编写权限、状态流转和异常输入自动化测试'),
('AI应用质量',4,['Python','pytest','RAG','评测','LangChain','Prompt工程','工具调用'],'知识问答评测','设计引用正确性、检索遗漏与工具异常样本'),
('Java初级',1,['Java','Spring Boot','MySQL','Git','单元测试'],'客户资料管理','完成新增查询、数据校验和测试用例'),
('Java企业管理',2,['Java','Spring Boot','MyBatis','MySQL','Vue'],'采购销售系统','实现采购入库、销售出库和库存流水'),
('Python脚本开发',2,['Python','SQL','HTTP API','pytest'],'数据导入工具','编写数据校验、接口调用与失败记录'),
('前端开发',2,['Vue','JavaScript','HTML/CSS','Git'],'运营后台','实现表单、分页列表和页面联调'),
('业务后端',6,['Java','Spring Boot','MySQL','Redis','JVM'],'企业业务平台','维护权限、报表、审计与慢查询治理'),
('检索与评测',4,['Python','RAG','向量检索','检索评测','Milvus','Elasticsearch','Rerank'],'混合检索评测','构建查询集、相关性标注和融合排序对比'),
('跨端应用',3,['React','TypeScript','Node.js','SQL','HTML/CSS'],'业务协作工作台','完成数据看板、接口联调和页面交互')]
# Four distinct fictional people per job. Scenarios are coverage, not gold hiring labels.
ASSIGN=[[0,2,18,8],[1,0,21,20],[18,9,17,13],[3,21,0,17],[11,22,1,8],[17,18,0,20],[4,22,2,19],[2,0,4,8],[5,6,4,19],[6,5,2,15],[7,4,19,20],[22,4,11,17],[8,10,23,0],[9,18,21,8],[10,8,5,20],[12,14,21,17],[13,19,15,0],[14,12,4,20],[15,16,19,8],[16,22,4,17]]
def main():
    (OUT/'jds').mkdir(parents=True,exist_ok=True)
    (OUT/'resumes').mkdir(exist_ok=True)
    candidates={}
    for i,(direction,years,skills,project,scope) in enumerate(PROFILES,1):
        code=f'D{i:02d}';name=f'演示候选人{code}'
        edu='大专' if i in (8,18,21) else '本科'
        text=f'''# {name}｜{direction}\n\n> 完全虚构，仅用于产品演示。不是任何真实候选人的履历。\n\n## 基本信息\n- 姓名：{name}\n- 工作年限：{years}年\n- 学历：{edu}\n- 最近公司：虚构演示团队{code}\n\n## 技能\n{ '、'.join(skills) }\n\n## 项目经历\n### {project}（虚构项目）\n承担{direction}相关开发，{scope}。参与需求拆分、接口联调和问题复盘，提供模块使用说明。\n\n### 模块质量改进（虚构项目）\n围绕已有模块补充输入校验、异常反馈和回归测试；保留处理记录，方便定位重复出现的问题。\n\n## 演示数据说明\n该档案由预设结构化字段生成，未通过AI简历解析，也未建立知识库向量。不包含真实联系方式。技能、年限和经历均为测试设定。\n'''
        candidates[code]={'kind':'SYNTHETIC_DEMO','candidateName':name,'yearsExp':years,'education':edu,'skills':skills,'lastCompany':f'虚构演示团队{code}','rawText':text}
        (OUT/'resumes'/f'{code}-{direction}.md').write_text(text,encoding='utf-8')
    jobs=[]
    for i,(title,dept,city,low,high,years,skills,bonus,scope,outcomes) in enumerate(JOBS,1):
        code=f'JD{i:02d}';full=f'[演示] {title}'
        desc=f'''【虚构演示岗位，不代表真实招聘或市场薪资】\n岗位：{title}\n团队：虚构{dept}团队\n地点：{city}\n测试薪资：{low}–{high}k/月，仅为界面与解析测试。\n\n岗位职责：\n1. 负责{scope}。\n2. {outcomes}。\n3. 与产品及测试人员确认验收标准，维护接口与交付说明。\n\n任职要求：\n1. 具备{years}年及以上相关项目经验，本科或同等项目能力。\n2. 能结合项目解释{'、'.join(skills)}的实际使用、异常边界与验证方法。\n3. 能说明个人负责的模块，不以技术名词出现次数代替实际能力。\n\n加分项：{'、'.join(bonus)}，不作为未满足即淘汰的依据。\n\n面试建议：围绕具体问题、处理过程和验证证据展开，核对简历中未明确的能力。最终判断由面试人与HR进行。'''
        payload={'title':full,'department':f'演示 · {dept}','city':city,'salaryMin':low,'salaryMax':high,'requiredSkills':skills,'preferredSkills':bonus,'minYears':years,'education':'本科','description':desc}
        jobs.append({'code':code,'payload':payload,'candidateIds':[f'D{n+1:02d}' for n in ASSIGN[i-1]]})
        (OUT/'jds'/f'{code}-{title.replace("｜","-")}.md').write_text(f'# {full}\n\n{desc}\n',encoding='utf-8')
    dataset={'kind':'SYNTHETIC_DEMO','version':1,'positions':jobs,'candidates':candidates}
    (OUT/'dataset.json').write_text(json.dumps(dataset,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Generated {len(jobs)} JDs, {len(candidates)} fictional profiles, {sum(len(j["candidateIds"]) for j in jobs)} position/profile associations')
if __name__=='__main__':main()
