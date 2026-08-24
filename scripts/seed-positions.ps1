# 批量创建岗位种子数据（用法：后端启动后执行 .\scripts\seed-positions.ps1）
# 注意：本文件必须保存为带 BOM 的 UTF-8，否则 PowerShell 5.1 会按 GBK 解析中文导致乱码
$base = "http://localhost:8080/api/v1"
$login = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}'
$headers = @{ Authorization = "Bearer $($login.token)" }
$utf8Json = "application/json; charset=utf-8"

$positions = @(
    @{
        title = "AI 应用开发工程师（Agent 方向）"
        department = "AI 创新部"
        city = "杭州"
        salaryMin = 25; salaryMax = 45
        requiredSkills = @("Java", "Spring Boot", "LangChain4j", "RAG", "MySQL")
        preferredSkills = @("LangGraph4j", "向量数据库", "Docker", "Prompt 工程")
        minYears = 3
        education = "本科"
        description = "负责企业级 AI Agent 应用的设计与开发：基于 LangChain4j 构建 RAG 检索增强系统，实现多轮对话、Function Calling 工具调用与多 Agent 协作编排；参与知识库管道（文档解析/切分/Embedding/向量化）建设；结合真实业务场景（智能招聘、智能客服）落地 LLM 能力，持续优化检索质量与生成效果。"
    },
    @{
        title = "高级 Java 后端工程师（中间件方向）"
        department = "交易平台部"
        city = "上海"
        salaryMin = 30; salaryMax = 55
        requiredSkills = @("Java", "Spring Boot", "Spring Cloud", "MySQL", "Redis")
        preferredSkills = @("Kafka", "RocketMQ", "Kubernetes", "分库分表", "JVM 调优")
        minYears = 5
        education = "本科"
        description = "负责核心交易系统的微服务架构设计与开发：高并发订单链路的稳定性与性能优化，缓存/消息/分布式事务等中间件的选型与落地，主导大促场景下的容量规划与应急预案建设，输出技术方案文档并推动团队技术演进。"
    },
    @{
        title = "全栈开发工程师（AI 产品）"
        department = "产品技术部"
        city = "深圳"
        salaryMin = 20; salaryMax = 38
        requiredSkills = @("React", "TypeScript", "Node.js", "MySQL")
        preferredSkills = @("Java", "Spring Boot", "SSE 流式渲染", "Ant Design", "Vite")
        minYears = 3
        education = "本科"
        description = "负责 AI 产品从 0 到 1 的全栈交付：React + Ant Design 前端工作台开发（SSE 流式对话、Markdown 渲染、数据可视化大屏），Node.js/Java BFF 层接口设计，与算法团队协作完成 LLM 能力的产品化封装。"
    },
    @{
        title = "AI 平台工程师（MLOps 方向）"
        department = "数据智能部"
        city = "北京"
        salaryMin = 35; salaryMax = 60
        requiredSkills = @("Python", "Docker", "Kubernetes", "大模型 API")
        preferredSkills = @("vLLM", "Qdrant", "Milvus", "LangChain", "CI/CD", "监控告警")
        minYears = 4
        education = "硕士"
        description = "负责 LLM 基础设施平台建设：模型服务的容器化部署与弹性伸缩（GPU 资源调度），向量数据库（Qdrant/Milvus）的运维与性能调优，推理网关/限流/计费体系设计，构建模型效果评测与监控大盘。"
    }
)

$ok = 0
foreach ($p in $positions) {
    $json = $p | ConvertTo-Json -Depth 5
    try {
        $resp = Invoke-RestMethod -Uri "$base/recruitment/positions" -Method Post -Headers $headers -ContentType $utf8Json -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
        Write-Output "[OK] #$($resp.id) $($resp.title)（$($resp.resumeCount) 份简历）"
        $ok++
    } catch {
        Write-Output "[FAIL] $($p.title): $($_.Exception.Message)"
        if ($_.ErrorDetails.Message) { Write-Output "  详情: $($_.ErrorDetails.Message)" }
    }
}
Write-Output "---"
Write-Output "成功创建 $ok / $($positions.Count) 个岗位"
