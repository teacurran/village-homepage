# @Schema Annotation Progress

## Summary

**Total Type Files:** 100
**Completed:** 20 files
**Remaining:** 80 files

## Completed Files (20)

The following files have been fully annotated with @Schema annotations:

1. ListingType.java
2. CategoryType.java
3. ContactInfoType.java
4. FeeScheduleType.java
5. UserProfileType.java
6. DirectorySiteType.java
7. FeedItemType.java
8. CreateListingRequestType.java
9. AiTagsType.java
10. RssSourceType.java
11. WeatherForecastType.java
12. CurrentWeatherType.java
13. HourlyForecastType.java
14. DailyForecastType.java
15. UpdateListingRequestType.java
16. FeatureFlagType.java
17. UpdateFeatureFlagRequestType.java
18. FeatureFlagEvaluationRequestType.java
19. FeatureFlagEvaluationResponseType.java
20. PromotionRequestType.java

## Annotation Pattern

Each Type file should follow this pattern:

### Record-Level Annotation
```java
@Schema(description = "Brief description of the type's purpose")
public record TypeName(...)
```

### Field-Level Annotations
```java
@Schema(
    description = "Field purpose",
    example = "realistic example value",
    required = true/false,       // true for @NotNull fields
    nullable = true/false,        // true for optional fields
    maxLength = 100,             // for strings where applicable
    enumeration = {"val1", "val2"}  // for fields with limited values
)
FieldType fieldName
```

## Remaining Files by Category

### Widget Types (6 files)
- LayoutWidgetType.java
- NewsWidgetType.java
- SocialWidgetStateType.java
- StockWidgetType.java ⭐ Priority
- WeatherWidgetType.java ⭐ Priority
- WidgetConfigType.java

### OAuth/Authentication Types (10 files)
- AppleIdTokenClaims.java
- AppleTokenResponseType.java
- FacebookPicture.java
- FacebookPictureData.java
- FacebookTokenResponseType.java
- FacebookUserInfoType.java
- GoogleTokenResponseType.java
- GoogleUserInfoType.java
- OAuthCallbackRequestType.java
- OAuthUrlResponseType.java

### Directory/Good Sites Types (10 files)
- CategorySiteType.java
- CreateDirectoryCategoryRequestType.java
- DirectoryAiSuggestionType.java
- DirectoryCategoryType.java ⭐ Priority
- DirectoryHomeType.java
- SiteDetailType.java
- SiteSubmissionResultType.java
- SitemapUrlType.java
- SubmitSiteType.java
- UpdateDirectoryCategoryRequestType.java

### Request/Update Types (18 files)
- AddArticleRequestType.java
- AssignRoleRequestType.java
- ContactInquiryRequest.java
- CreateCategoryRequestType.java
- CreateDirectoryCategoryRequestType.java
- CreateProfileRequestType.java
- CreateRssSourceRequestType.java
- NotificationPreferencesUpdateType.java
- OAuthCallbackRequestType.java
- RefundActionRequestType.java
- SubmitFlagRequestType.java
- UpdateArticleCustomizationType.java
- UpdateCategoryRequestType.java
- UpdateDirectoryCategoryRequestType.java
- UpdateProfileRequestType.java
- UpdateRateLimitConfigRequestType.java
- UpdateRssSourceRequestType.java
- UpdateTemplateRequestType.java

### Marketplace/Search Types (3 files)
- SearchCriteria.java ⭐ Priority
- SearchResultsType.java ⭐ Priority
- ListingSearchResultType.java ⭐ Priority

### Payment/Stripe Types (5 files)
- PaymentIntentResponseType.java
- RefundActionRequestType.java
- RefundType.java
- StripeEventType.java
- FraudAnalysisResultType.java

### Admin/Moderation Types (8 files)
- AdminKarmaAdjustmentType.java
- AdminTrustLevelChangeType.java
- AssignRoleRequestType.java
- ImpersonationAuditType.java
- KarmaSummaryType.java
- ModerationStatsType.java
- SubmitFlagRequestType.java
- FlagType.java

### User/Profile Types (6 files)
- CreateProfileRequestType.java
- ProfileCuratedArticleType.java
- SlotAssignmentType.java
- UpdateProfileRequestType.java
- UserPreferencesType.java
- UserRoleType.java

### Miscellaneous/Utility Types (14 files)
- AiCategorySuggestionType.java
- AiUsageReportType.java
- AiUsageType.java
- CacheStatsType.java
- CategoryViewType.java
- ClickEventType.java
- CsvUploadForm.java
- FeedItemTaggingResultType.java
- ImageUploadForm.java
- ListingCategorizationResultType.java
- ListingImageType.java
- NotificationPreferencesType.java
- RateLimitConfigType.java
- RateLimitViolationType.java
- SignedUrlType.java
- SocialPostType.java
- StorageObjectType.java
- StorageUploadResultType.java
- ThemeType.java
- WeatherAlertType.java
- WeatherLocationType.java

## Next Steps

To complete the annotation process:

1. ⭐ **Priority files** (14 files marked above) should be done first as they're used in main endpoints
2. Process files in category groups for consistency
3. Use existing annotated files as templates for similar types
4. Ensure all examples use realistic, domain-appropriate values
5. Mark required=true for @NotNull fields, nullable=true for optional fields
6. Add maxLength for string fields where validation constraints exist

## Import Statement

All annotated files need this import:
```java
import org.eclipse.microprofile.openapi.annotations.media.Schema;
```
