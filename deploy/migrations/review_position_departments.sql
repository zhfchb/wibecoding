-- Read-only review only: candidate names are NOT approved mappings.
-- Missing membership and unmatched names remain unresolved. Never UPDATE from this query.
SELECT p.id AS position_id,p.user_id AS creator_user_id,p.department AS legacy_department,
       m.company_id AS membership_company_id,d.id AS candidate_department_id,d.name AS candidate_name,d.status,
       CASE WHEN m.company_id IS NULL THEN 'MEMBERSHIP_UNRESOLVED'
            WHEN d.id IS NULL THEN 'MANUAL_REVIEW_REQUIRED' ELSE 'CANDIDATE_ONLY' END AS review_state
FROM job_position p
LEFT JOIN recruitment_company_member m ON m.user_id=p.user_id
LEFT JOIN recruitment_department d ON d.company_id=m.company_id AND d.name=TRIM(p.department)
WHERE p.department_id IS NULL
ORDER BY p.id,d.id;
