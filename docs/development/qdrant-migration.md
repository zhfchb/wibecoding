# Qdrant 集合 Schema 迁移说明

本文件记录 wibecoding 项目 Qdrant `knowledge` 集合的 schema 演进与升级步骤。

> 关系库表（document / chat_history / sys_user）由 `src/main/resources/schema.sql` 管理，Spring Boot 启动自动执行。
> Qdrant 不是关系库，集合 schema 需单独管理，由 `scripts/qdrant-setup.ps1` 负责初始化。

## Schema 版本

### v1（M4，已废弃）

- 集合使用**默认未命名向量**（unnamed vector）
- 距离度量：Cosine
- 维度：768（hash baseline）/ 1024（BGE-M3）
- 仅支持稠密检索（`POST /collections/{name}/points/search`）

### v2（M7，当前）

- 集合使用**命名向量**：
  - `dense`：稠密向量，Cosine，维度 1024（匹配 BGE-M3）
  - `sparse`：稀疏向量，Qdrant 原生 `sparse_vectors`，用于 BM25 风格关键词召回
- 检索改用 `POST /collections/{name}/points/query`，两路 `prefetch`（dense + sparse）+ `fusion.rrf(k=60)` 原生融合
- payload 新增 `file_name`、`chunk_index` 字段（来源归因）

## 从 v1 升级到 v2

**不兼容变更**：v1 的默认未命名向量与 v2 的命名向量 `dense` 无法混用，必须删除重建。

### 步骤

1. 确认 Qdrant 在运行：

   ```powershell
   docker compose ps qdrant
   # 或
   docker ps --filter "name=qdrant"
   ```

2. 用迁移脚本重建集合（`-Recreate` 会先删除旧集合，不可逆，会清空向量数据）：

   ```powershell
   .\scripts\qdrant-setup.ps1 -Recreate
   ```

   如果只是首次创建（无旧集合），省略 `-Recreate`：

   ```powershell
   .\scripts\qdrant-setup.ps1
   ```

3. 重新上传知识库文档（旧向量数据已清空）：
   - 启动应用后调用 `POST /api/v1/knowledge/upload` 重新导入文档
   - 新写入的切片会自动包含 `dense` + `sparse` 向量与 `file_name`/`chunk_index` payload

4. 验证集合 schema：

   ```powershell
   Invoke-RestMethod http://localhost:6333/collections/knowledge | ConvertTo-Json -Depth 5
   ```

   应看到 `config.params.vectors.dense` 和 `config.params.sparse_vectors.sparse`。

## 回滚（不建议）

如需回退到 v1（仅稠密检索），需要：
1. `git checkout` 到 M6 及之前的代码
2. 删除集合：`Invoke-RestMethod -Method DELETE http://localhost:6333/collections/knowledge`
3. 重启应用，旧代码会按 v1 schema 自动创建集合

回滚后丢失 M7 的混合检索与 rerank 能力，不推荐。

## 脚本参数

`scripts/qdrant-setup.ps1` 支持的参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-QdrantUrl` | `http://localhost:6333` | Qdrant HTTP 地址 |
| `-Collection` | `knowledge` | 集合名 |
| `-Dimension` | `1024` | 稠密向量维度（BGE-M3=1024） |
| `-Recreate` | 关 | 开关：先删除再重建 |

示例：

```powershell
.\scripts\qdrant-setup.ps1
.\scripts\qdrant-setup.ps1 -Recreate
.\scripts\qdrant-setup.ps1 -QdrantUrl http://localhost:6333 -Collection knowledge -Dimension 1024 -Recreate
```
