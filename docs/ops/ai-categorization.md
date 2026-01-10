# AI Categorization for Good Sites Directory

This document describes the AI-powered category suggestion system for bulk import of Good Sites directory entries (Feature F13.14).

## Overview

The bulk import system uses Claude Sonnet 4 via LangChain4j to analyze website metadata (URL, title, description) and suggest 1-3 most appropriate categories from the Good Sites taxonomy. Admins review AI suggestions before site creation, enabling efficient bulk curation while maintaining quality control.

## System Architecture

### Components

1. **AiCategorizationService** - Core AI logic for category suggestion
2. **BulkImportJobHandler** - Async job processor for CSV imports
3. **DirectoryImportResource** - Admin REST API for upload/review
4. **DirectoryAiSuggestion** - Entity storing suggestions pending admin review
5. **AiTaggingBudgetService** - Shared budget enforcement across all AI operations

### Data Flow

```
Admin uploads CSV
      ↓
DirectoryImportResource validates & stores to /tmp
      ↓
BulkImportJobHandler enqueued (BULK queue)
      ↓
Phase 1: Parse CSV rows, create DirectoryAiSuggestion records
      ↓
Phase 2: For each suggestion:
   - Check budget action (NORMAL/REDUCE/QUEUE/HARD_STOP)
   - Call AiCategorizationService
   - Build category tree JSON for prompt context
   - Send to Claude Sonnet 4 via LangChain4j
   - Parse JSON response with confidence & reasoning
   - Validate category slugs against database
   - Store suggestion with token/cost tracking
      ↓
Admin reviews suggestions via /admin/api/directory/import/suggestions
      ↓
Admin approves (with optional category override) or rejects
      ↓
On approval: Create DirectorySite + DirectorySiteCategory records
```

## AI Categorization Prompt Template

### Input Context

The prompt includes:
- **Full category taxonomy** as nested JSON (7 root categories, ~130 subcategories)
- **Website URL** (e.g., `https://github.com`)
- **Title** (e.g., "GitHub - Code Hosting Platform")
- **Description** (optional, from CSV or OpenGraph metadata)

### Prompt Structure

```
You are an expert web directory curator. Your task is to analyze a website and suggest the most appropriate categories from a hierarchical taxonomy.

TAXONOMY:
{category_tree_json}

WEBSITE TO CATEGORIZE:
URL: {url}
Title: {title}
Description: {description}

INSTRUCTIONS:
1. Analyze the website's purpose, content, and target audience based on URL, title, and description
2. Select 1-3 most SPECIFIC categories from the taxonomy (prefer leaf categories over parents)
3. Provide clear reasoning for each category selection
4. Assign confidence score (0.0-1.0) based on clarity of website purpose and metadata quality

CONSTRAINTS:
- Categories MUST exist in the provided taxonomy (use exact slugs)
- Prefer the MOST SPECIFIC category (e.g., "computers-programming-java" over "computers-programming" or "computers")
- If website clearly fits multiple categories, suggest up to 3 (ordered by relevance)
- If purpose is ambiguous or metadata is insufficient, prefer broader categories with lower confidence
- If URL/title/description provide minimal information, suggest general categories with confidence < 0.5

OUTPUT FORMAT (strict JSON, no markdown):
{
  "suggested_categories": [
    {
      "category_slug": "computers-programming-java",
      "category_path": "Computers > Programming > Java",
      "reasoning": "Website appears to be a Java-specific tutorial and documentation site based on URL and title"
    }
  ],
  "confidence": 0.85,
  "overall_reasoning": "Clear technical content focused on Java programming with specific keywords in title"
}

Respond with ONLY valid JSON (no markdown code blocks, no explanation text).
```

### Category Tree JSON Format

Example structure:

```json
[
  {
    "slug": "computers",
    "name": "Computers",
    "description": "Computer hardware, software, and technology",
    "children": [
      {
        "slug": "computers-programming",
        "name": "Programming",
        "description": "Software development and coding",
        "children": [
          {
            "slug": "computers-programming-java",
            "name": "Java",
            "description": "Java programming language and frameworks"
          }
        ]
      }
    ]
  }
]
```

## CSV Import Format

### Required Columns

- **url** (required): Website URL to import

### Optional Columns

- **title**: Override fetched title
- **description**: Override fetched description
- **suggested_categories**: Comma-separated category slugs (manual override, bypasses AI)

### Example CSV

```csv
url,title,description,suggested_categories
https://github.com,GitHub,Developer platform for code hosting,
https://stackoverflow.com,Stack Overflow,Q&A for programmers,computers-programming
https://news.ycombinator.com,Hacker News,Tech news aggregator,news-technology
```

### Import Constraints

- **Maximum file size**: 10 MB
- **Maximum rows**: 10,000 per upload
- **Duplicate detection**: URLs matching existing DirectorySite or DirectoryAiSuggestion are skipped
- **URL normalization**: All URLs normalized to lowercase HTTPS with trailing slash removed

## Budget Enforcement (Policy P2/P10)

### Shared Budget Pool

AI categorization shares a **$500/month** budget with:
- Feed item tagging (`AiTaggingJobHandler`)
- Fraud detection (future)

### Budget Thresholds

| Threshold | % Used | Action | Batch Size | Behavior |
|-----------|--------|--------|------------|----------|
| NORMAL | < 75% | Process at full speed | 20 items | Standard operation |
| REDUCE | 75-90% | Slow down | 10 items | Conserve budget |
| QUEUE | 90-100% | Defer processing | 0 | Skip job, retry next hour |
| HARD_STOP | > 100% | Stop all AI ops | 0 | No operations until next month |

### Monitoring

- **Prometheus metrics**:
  - `ai.categorization.suggestions.total{status="categorized|failure"}`
  - `ai.categorization.csv.rows.total{status="parsed|duplicate"}`
  - `ai.categorization.budget.throttles`
  - `ai.budget.percent_used` (gauge)

- **Alert thresholds**:
  - Email to ops@villagecompute.com at 75%, 90%, 100%
  - Grafana dashboard: "AI Budget Usage"

## Cost Estimation

### Token Pricing (Claude Sonnet 4)

- **Input tokens**: $3.00 per 1M tokens ($0.000003 per token)
- **Output tokens**: $15.00 per 1M tokens ($0.000015 per token)

### Typical Categorization Cost

- **Input tokens**: ~2,000 (category tree + URL/title/description)
- **Output tokens**: ~200 (JSON response with 1-3 categories)
- **Estimated cost per site**: ~$0.01 (1 cent)

### Budget Capacity

At $0.01 per site:
- **$500/month budget** = ~50,000 sites per month
- **Shared with feed tagging**: Assume 50/50 split = ~25,000 sites per month
- **Daily capacity**: ~800 sites per day (assuming even distribution)

## Admin Workflow

### 1. Upload CSV

```bash
POST /admin/api/directory/import/upload
Content-Type: multipart/form-data

file: upload.csv
description: "Tech news sites batch #3"
```

**Response:**

```json
{
  "job_id": 12345,
  "rows": 150,
  "status": "queued",
  "message": "CSV uploaded successfully, processing in background"
}
```

### 2. Monitor Processing

Check job status via logs or Grafana dashboard. Processing time depends on budget action:
- **NORMAL (< 75%)**: 20 sites/batch, ~1 minute per batch (15 seconds per batch × 8 batches for 150 sites)
- **REDUCE (75-90%)**: 10 sites/batch, ~2 minutes per batch
- **QUEUE (90-100%)**: Deferred to next hour

### 3. Review Suggestions

```bash
GET /admin/api/directory/import/suggestions?status=pending
```

**Response:**

```json
[
  {
    "id": "uuid",
    "url": "https://github.com",
    "domain": "github.com",
    "title": "GitHub",
    "description": "Developer platform for code hosting",
    "ai_suggested_categories": [
      {
        "category_id": "uuid",
        "category_slug": "computers-programming",
        "category_path": "Computers > Programming",
        "reasoning": null
      }
    ],
    "admin_selected_categories": null,
    "confidence": 0.85,
    "reasoning": "Clear technical content focused on code hosting and version control",
    "status": "pending",
    "tokens_input": 2100,
    "tokens_output": 180,
    "estimated_cost_cents": 1,
    "created_at": "2025-01-10T10:00:00Z"
  }
]
```

### 4. Approve or Override

**Accept AI suggestion:**

```bash
POST /admin/api/directory/import/suggestions/{id}/approve
Content-Type: application/x-www-form-urlencoded

# Empty body = use AI suggestion
```

**Override with different categories:**

```bash
POST /admin/api/directory/import/suggestions/{id}/approve
Content-Type: application/x-www-form-urlencoded

category_ids=uuid1,uuid2,uuid3
```

**Reject:**

```bash
POST /admin/api/directory/import/suggestions/{id}/reject
```

### 5. Training Data Collection

When admin overrides AI suggestion, both are stored for future training:

```json
{
  "ai_suggested_categories": [
    {"category_slug": "computers-programming", ...}
  ],
  "admin_selected_categories": [
    {"category_slug": "computers-opensource", ...}
  ]
}
```

This diff is accessible via:

```bash
GET /admin/api/directory/import/suggestions/{id}
```

Use `had_override: true` in approval response to identify training data candidates.

## Troubleshooting

### Low Confidence Scores (< 0.5)

**Symptom**: Many suggestions have confidence < 0.5

**Causes**:
- Insufficient metadata (title/description missing or vague)
- Ambiguous website purpose (e.g., personal blogs, portfolios)
- Category taxonomy doesn't match website type

**Solutions**:
- Enhance CSV with better titles/descriptions
- Review category tree for missing categories
- Manual categorization for edge cases

### Invalid Category Slugs

**Symptom**: AI suggests categories that don't exist in database

**Cause**: Hallucination (Claude invents category slugs)

**Solution**: Automatic filtering in `AiCategorizationService.parseResponse()` skips invalid slugs. If all suggested categories are invalid, suggestion remains pending with `suggestedCategoryIds = null` for manual review.

### Budget Exhaustion Mid-Batch

**Symptom**: Job stops processing after N items, logs "Budget exhausted mid-processing"

**Cause**: Budget threshold crossed during job execution

**Solution**: Job automatically pauses and resumes on next run. No data loss - unprocessed suggestions remain with `suggestedCategoryIds = null` status.

### CSV Upload Fails

**Symptom**: 400 Bad Request on upload

**Causes**:
- File size > 10 MB
- Row count > 10,000
- Invalid CSV format
- Non-CSV file extension

**Solution**: Split large files, validate CSV structure, ensure `.csv` extension.

### Duplicate URLs Skipped

**Symptom**: Fewer rows processed than uploaded

**Cause**: Duplicate detection skips URLs already in `directory_sites` or `directory_ai_suggestions` tables

**Solution**: Check logs for `"Skipping duplicate URL"` entries. This is expected behavior - duplicates are tracked via `ai.categorization.csv.rows.total{status="duplicate"}` metric.

## Performance Tuning

### Batch Size Optimization

Default batch sizes (20/10/0) are optimized for:
- Budget conservation
- API rate limiting
- Error isolation (single failure doesn't fail entire batch)

To adjust, modify `AiTaggingBudgetService.getBatchSize()`.

### Category Tree Size

Full category tree JSON is ~10KB (7 roots, 130 subcategories). Monitor input token counts if tree grows beyond 200 categories.

Optimization: Include only active categories with `isActive = true` filter.

### CSV Processing Speed

- **Parsing phase**: ~1000 rows/second (disk I/O bound)
- **AI categorization phase**: ~20-80 rows/minute (API latency bound)

For 10,000-row CSV:
- Parsing: ~10 seconds
- Categorization (NORMAL): ~2 hours (20 rows/batch × 500 batches × 15s/batch)
- Categorization (REDUCE): ~4 hours (10 rows/batch)

## Security Considerations

### Access Control

All endpoints secured with `@RolesAllowed({User.ROLE_SUPER_ADMIN, User.ROLE_OPS})`.

### CSV Injection Prevention

- URLs normalized and validated
- Title/description sanitized (XSS prevention)
- No eval() or shell execution on CSV data

### Temp File Cleanup

CSV files stored to `/tmp` with unique names:
- Format: `bulk-import-{timestamp}-{original-filename}`
- Deleted after successful processing
- Orphaned files cleaned by OS tmpdir policy (typically 10 days)

### Rate Limiting

Bulk import uses BULK queue (lowest priority). No per-user rate limits - admins trusted to self-regulate.

## Future Enhancements

### Active Learning Pipeline

- Export override data (AI vs admin selections)
- Fine-tune Claude with correction examples
- Measure improvement via confidence score trends

### Auto-Approval for High Confidence

If `confidence >= 0.95`, auto-approve without admin review. Requires:
- Policy approval
- Audit trail enhancement
- Revert mechanism

### Multi-Language Support

Current prompt is English-only. For international sites:
- Detect language from metadata
- Translate to English for categorization
- Map to localized category names

### Fallback Categorization

If Claude unavailable or budget exhausted:
- Use keyword-based heuristics (current stub implementation)
- Mark with `confidence = 0.3`
- Flag for manual review

## References

- **Feature F13.14**: Bulk Import with AI Categorization
- **Policy P2/P10**: AI Budget Control ($500/month ceiling)
- **Claude Sonnet 4 Docs**: https://docs.anthropic.com/claude/docs/models-overview
- **LangChain4j Guide**: https://docs.langchain4j.dev/

## Changelog

- **2025-01-10**: Initial implementation (I5.T6)
