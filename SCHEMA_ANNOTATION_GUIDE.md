# @Schema Annotation Guide for Remaining Type Files

## Current Status
- **Total Files:** 100
- **Completed:** 25
- **Remaining:** 75

## Annotation Pattern & Examples

### 1. Import Statement
Add this import to every Type file:
```java
import org.eclipse.microprofile.openapi.annotations.media.Schema;
```

### 2. Record-Level Annotation
Add description at record level:
```java
@Schema(description = "Brief clear description of what this type represents")
public record TypeName(...) {
```

### 3. Field-Level Annotations

#### Required Fields (with @NotNull, @NotBlank)
```java
@Schema(description = "Field purpose", example = "example value", required = true)
@NotNull FieldType fieldName
```

#### Optional Fields (nullable)
```java
@Schema(description = "Field purpose", example = "example value", nullable = true)
FieldType fieldName
```

#### String Fields with Length Constraints
```java
@Schema(description = "Field purpose", example = "example", required = true, maxLength = 100)
@NotNull @Size(min = 10, max = 100) String fieldName
```

#### Enum/Limited Values
```java
@Schema(description = "Field purpose", example = "value1",
        enumeration = {"value1", "value2", "value3"}, required = true)
@NotNull String status
```

#### Timestamps
```java
@Schema(description = "Timestamp description", example = "2026-01-24T10:00:00Z", required = true)
@NotNull Instant timestamp
```

#### UUIDs
```java
@Schema(description = "UUID description", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
@NotNull UUID id
```

#### Numeric Fields
```java
@Schema(description = "Numeric field", example = "42", required = true)
@NotNull Integer count
```

#### Decimal/Money Fields
```java
@Schema(description = "Price in USD", example = "99.99", nullable = true)
BigDecimal price
```

#### Boolean Fields
```java
@Schema(description = "Boolean flag", example = "true", required = true)
@NotNull Boolean isActive
```

#### List/Array Fields
```java
@Schema(description = "List of items", required = true)
@NotNull List<String> items
```

#### Nested Type Fields
```java
@Schema(description = "Nested object", required = true)
@NotNull @Valid NestedType nested
```

## Category-Specific Templates

### Weather Widget Types

Example for `WeatherWidgetType.java`:
```java
@Schema(description = "Complete weather widget data with current conditions and forecasts")
public record WeatherWidgetType(
    @Schema(description = "Current weather conditions", required = true)
    @NotNull CurrentWeatherType current,

    @Schema(description = "Hourly forecast for next 24 hours", required = true)
    @NotNull List<HourlyForecastType> hourly,

    @Schema(description = "Daily forecast for next 7 days", required = true)
    @NotNull List<DailyForecastType> daily,

    @Schema(description = "Active weather alerts", nullable = true)
    List<WeatherAlertType> alerts,

    @Schema(description = "Location for this forecast", required = true)
    @NotNull WeatherLocationType location
) {}
```

### OAuth Types

Example for `GoogleUserInfoType.java`:
```java
@Schema(description = "Google user information from OAuth response")
public record GoogleUserInfoType(
    @Schema(description = "Google user ID", example = "1234567890", required = true)
    @NotNull String id,

    @Schema(description = "User email address", example = "user@gmail.com", required = true)
    @NotNull String email,

    @Schema(description = "Whether email is verified", example = "true", required = true)
    @NotNull Boolean emailVerified,

    @Schema(description = "User's full name", example = "John Doe", nullable = true)
    String name,

    @Schema(description = "Profile picture URL", example = "https://lh3.googleusercontent.com/...", nullable = true)
    String picture
) {}
```

### Directory/Site Types

Example for `DirectoryCategoryType.java`:
```java
@Schema(description = "Web directory category with hierarchical structure")
public record DirectoryCategoryType(
    @Schema(description = "Unique category identifier", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    @NotNull UUID id,

    @Schema(description = "Parent category UUID (null for root)", example = "660e8400-e29b-41d4-a716-446655440001", nullable = true)
    UUID parentId,

    @Schema(description = "Category name", example = "Technology", required = true, maxLength = 100)
    @NotNull String name,

    @Schema(description = "URL-friendly slug", example = "technology", required = true, maxLength = 100)
    @NotNull String slug,

    @Schema(description = "Category description", example = "Technology news and resources", nullable = true, maxLength = 500)
    String description,

    @Schema(description = "Display sort order", example = "10", required = true)
    @NotNull Integer sortOrder,

    @Schema(description = "Site count in this category", example = "42", required = true)
    @NotNull Long siteCount,

    @Schema(description = "Creation timestamp", example = "2026-01-24T10:00:00Z", required = true)
    @NotNull Instant createdAt
) {}
```

### Request Types (Create/Update)

Example for `CreateCategoryRequestType.java`:
```java
@Schema(description = "Request to create a new marketplace category")
public record CreateCategoryRequestType(
    @Schema(description = "Parent category UUID (null for root)", example = "660e8400-e29b-41d4-a716-446655440001", nullable = true)
    UUID parentId,

    @Schema(description = "Category name (3-100 characters)", example = "Electronics", required = true, maxLength = 100)
    @NotNull @Size(min = 3, max = 100) String name,

    @Schema(description = "URL-friendly slug", example = "electronics", required = true, maxLength = 100)
    @NotNull String slug,

    @Schema(description = "Display sort order", example = "10", required = true)
    @NotNull Integer sortOrder,

    @Schema(description = "Fee schedule for this category", required = true)
    @NotNull FeeScheduleType feeSchedule
) {}
```

### Admin/Moderation Types

Example for `AdminKarmaAdjustmentType.java`:
```java
@Schema(description = "Admin karma adjustment with reason and audit trail")
public record AdminKarmaAdjustmentType(
    @Schema(description = "User ID receiving adjustment", example = "12345", required = true)
    @NotNull Long userId,

    @Schema(description = "Karma delta (positive or negative)", example = "10", required = true)
    @NotNull Integer karmaDelta,

    @Schema(description = "Reason for adjustment", example = "Quality contribution", required = true, maxLength = 500)
    @NotNull String reason,

    @Schema(description = "Admin user ID performing adjustment", example = "1", required = true)
    @NotNull Long adminId,

    @Schema(description = "Adjustment timestamp", example = "2026-01-24T10:00:00Z", required = true)
    @NotNull Instant createdAt
) {}
```

### User/Profile Types

Example for `UserPreferencesType.java`:
```java
@Schema(description = "User preferences and settings")
public record UserPreferencesType(
    @Schema(description = "Preferred theme", example = "dark", enumeration = {"light", "dark", "auto"}, required = true)
    @NotNull String theme,

    @Schema(description = "Email notification preferences", required = true)
    @NotNull NotificationPreferencesType notifications,

    @Schema(description = "Default homepage layout", nullable = true)
    Map<String, Object> defaultLayout,

    @Schema(description = "Preferred temperature unit", example = "fahrenheit", enumeration = {"fahrenheit", "celsius"}, required = true)
    @NotNull String temperatureUnit,

    @Schema(description = "Whether user has granted analytics consent", example = "false", required = true)
    @NotNull Boolean analyticsConsent
) {}
```

### Payment/Stripe Types

Example for `PaymentIntentResponseType.java`:
```java
@Schema(description = "Stripe payment intent response with client secret")
public record PaymentIntentResponseType(
    @Schema(description = "Stripe payment intent ID", example = "pi_1234567890", required = true)
    @NotNull String paymentIntentId,

    @Schema(description = "Client secret for frontend", example = "pi_1234567890_secret_abcdef", required = true)
    @NotNull String clientSecret,

    @Schema(description = "Amount in cents", example = "500", required = true)
    @NotNull Long amount,

    @Schema(description = "Currency code", example = "usd", required = true)
    @NotNull String currency,

    @Schema(description = "Payment status", example = "requires_payment_method",
            enumeration = {"requires_payment_method", "requires_confirmation", "requires_action", "processing", "succeeded", "canceled"}, required = true)
    @NotNull String status
) {}
```

### Utility/Support Types

Example for `StorageObjectType.java`:
```java
@Schema(description = "Cloud storage object metadata")
public record StorageObjectType(
    @Schema(description = "Object key/path", example = "listings/abc123.jpg", required = true)
    @NotNull String key,

    @Schema(description = "Public URL", example = "https://r2.villagecompute.com/listings/abc123.jpg", required = true)
    @NotNull String url,

    @Schema(description = "Content type", example = "image/jpeg", required = true)
    @NotNull String contentType,

    @Schema(description = "Size in bytes", example = "1024000", required = true)
    @NotNull Long size,

    @Schema(description = "Upload timestamp", example = "2026-01-24T10:00:00Z", required = true)
    @NotNull Instant uploadedAt
) {}
```

## Systematic Approach for Remaining Files

### Priority 1: High-Traffic Endpoint Types (Complete These First)
1. WeatherWidgetType.java
2. WeatherAlertType.java
3. WeatherLocationType.java
4. DirectoryCategoryType.java
5. CategorySiteType.java
6. SiteDetailType.java

### Priority 2: OAuth & Authentication
7. GoogleUserInfoType.java
8. GoogleTokenResponseType.java
9. FacebookUserInfoType.java
10. FacebookTokenResponseType.java
11. FacebookPicture.java
12. FacebookPictureData.java
13. AppleIdTokenClaims.java
14. AppleTokenResponseType.java
15. OAuthCallbackRequestType.java
16. OAuthUrlResponseType.java

### Priority 3: Request Types
17-34. All *RequestType.java files (CreateXXX, UpdateXXX, etc.)

### Priority 4: Admin & Moderation
35-42. Admin* and moderation types

### Priority 5: Remaining Support Types
43-75. All utility, configuration, and supporting types

## Quick Reference: Common Examples

### UUID Examples
```
"550e8400-e29b-41d4-a716-446655440000"
"660e8400-e29b-41d4-a716-446655440001"
"770e8400-e29b-41d4-a716-446655440002"
```

### Timestamp Examples
```
"2026-01-24T10:00:00Z"
"2026-01-24T12:30:00Z"
"2026-01-24T16:00:00Z"
```

### Money Examples
```
"99.99"
"15999.99"
"5.00"
```

### URL Examples
```
"https://example.com/path"
"https://cdn.villagecompute.com/image.jpg"
"https://r2.villagecompute.com/screenshots/abc123.png"
```

### Email Examples
```
"user@example.com"
"admin@villagecompute.com"
"seller@example.com"
```

## Validation Checklist

For each annotated file, verify:
- [ ] Import statement added
- [ ] Record-level @Schema annotation present
- [ ] All fields have @Schema annotations
- [ ] Required fields marked with `required = true`
- [ ] Optional fields marked with `nullable = true`
- [ ] String fields have realistic `maxLength` values
- [ ] Enum-like fields have `enumeration` array
- [ ] Examples are realistic and domain-appropriate
- [ ] UUID examples use valid format
- [ ] Timestamps use ISO-8601 format
- [ ] Money values use decimal format

## Automation Tip

You can use this sed pattern to add the import (run from types directory):
```bash
for file in *.java; do
    if ! grep -q "@Schema" "$file"; then
        # Add import after other jakarta imports
        sed -i '' '/import jakarta/a\
import org.eclipse.microprofile.openapi.annotations.media.Schema;
' "$file"
    fi
done
```

Then manually add the annotations following the patterns above.
