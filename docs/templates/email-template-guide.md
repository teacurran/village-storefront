# Email Template Guide

## Overview

This guide explains how to create, edit, and maintain email notification templates in the Village Storefront platform. All email templates use Qute (Quarkus Template Engine) with responsive HTML design and inline CSS for maximum email client compatibility.

## Table of Contents

1. [Template Structure](#template-structure)
2. [Localization](#localization)
3. [Adding New Email Types](#adding-new-email-types)
4. [Template Best Practices](#template-best-practices)
5. [Testing Templates](#testing-templates)
6. [Domain Filtering](#domain-filtering)

---

## Template Structure

### File Locations

All email templates are located in:
```
src/main/resources/templates/email/
├── consignment/        # Consignment lifecycle emails
│   ├── intake-confirmation.html
│   ├── sale-notification.html
│   ├── payout-summary.html
│   └── expiration-alert.html
└── order/             # Order lifecycle emails
    ├── order-confirmation.html
    ├── order-shipped.html
    ├── order-delivered.html
    └── order-cancelled.html
```

### Base Template Structure

Every email template follows this structure:

```html
<!DOCTYPE html>
<html lang="{locale ?: 'en'}">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <style>
        /* Inline CSS for email client compatibility */
        body { margin: 0; padding: 0; font-family: -apple-system, sans-serif; }
        .email-wrapper { max-width: 600px; margin: 0 auto; background-color: #ffffff; }
        .email-header { background-color: #3b82f6; color: #ffffff; padding: 32px 24px; }
        .email-body { padding: 32px 24px; }
        .email-footer { padding: 24px; text-align: center; color: #6b7280; }
        /* ... more styles ... */
    </style>
</head>
<body>
    <div class="email-wrapper">
        <!-- Header -->
        <div class="email-header">
            <h1>{msg.email_template_header}</h1>
        </div>

        <!-- Body Content -->
        <div class="email-body">
            <h2>{msg.email_template_greeting} {customerName},</h2>
            <p>{msg.email_template_intro}</p>
            <!-- Template-specific content -->
        </div>

        <!-- Footer -->
        <div class="email-footer">
            <p>{msg.email_footer_message}</p>
            <p>&copy; 2026 {msg.all_rights_reserved}</p>
        </div>
    </div>
</body>
</html>
```

### Available Qute Variables

All templates have access to:

- **`msg`** - Map of localized message strings (loaded from `messages.properties`)
- **`locale`** - Current locale (e.g., "en", "es")
- **`consignorName`** - Consignor name (for consignment emails)
- **`customerName`** - Customer name (for order emails)
- **Template-specific data** - Passed via `templateData` map

---

## Localization

### Message Bundle Structure

Message keys are defined in:
- **English**: `src/main/resources/messages/messages.properties`
- **Spanish**: `src/main/resources/messages/messages_es.properties`

### Message Key Naming Convention

```properties
# Subject lines (used in NotificationService)
email.{category}.{type}.subject=Email Subject

# Template content keys
email_{category}_{type}_{element}=Content
```

**Examples:**
```properties
# Order confirmation email
email.order.order_confirmation.subject=Order Confirmation - Thank You for Your Order
email_order_confirmation_header=Order Confirmation
email_order_confirmation_greeting=Hi
email_order_confirmation_intro=Thank you for your order!
```

### Using Localized Strings in Templates

```html
<!-- Display localized message -->
<h1>{msg.email_order_confirmation_header}</h1>

<!-- Concatenate messages with variables -->
<h2>{msg.email_order_confirmation_greeting} {customerName},</h2>

<!-- Conditional content -->
{#if trackingNumber}
    <p>{msg.email_tracking_number}: {trackingNumber}</p>
{/if}
```

### Supported Locales

- **`en`** - English (default fallback)
- **`es`** - Spanish

**Fallback behavior:** If a requested locale is not available, the system automatically falls back to English.

---

## Adding New Email Types

### Step 1: Define Template Type

Add a new enum value to `EmailTemplateType.java`:

```java
/**
 * Sent when [describe trigger event].
 *
 * <p>
 * Template: {@code email/[category]/[template-name].html}
 * <p>
 * Feature flag: {@code notifications.[category].[type]}
 */
NEW_EMAIL_TYPE("email/[category]/[template-name].html", "notifications.[category].[type]");
```

### Step 2: Create HTML Template

Create a new file in `src/main/resources/templates/email/[category]/[template-name].html` following the base structure.

### Step 3: Add Localization Keys

Add keys to both `messages.properties` and `messages_es.properties`:

```properties
# Subject line
email.[category].[type].subject=Email Subject

# Template content
email_[type]_header=Header Text
email_[type]_greeting=Greeting
email_[type]_intro=Introduction paragraph
```

### Step 4: Inject Template in NotificationService

Add template injection in `NotificationService.java`:

```java
@Inject
@Location("email/[category]/[template-name].html")
Template newEmailTemplate;
```

Update `templateFor()` method:

```java
private Template templateFor(EmailTemplateType type) {
    switch (type) {
        case NEW_EMAIL_TYPE:
            return newEmailTemplate;
        // ... other cases
    }
}
```

### Step 5: Add Send Method

Add a public send method in `NotificationService.java`:

```java
/**
 * Send [description] notification to [recipient].
 *
 * <p>
 * Expected template data keys:
 * <ul>
 * <li>{@code key1}: description</li>
 * <li>{@code key2}: description</li>
 * </ul>
 *
 * @param context notification context with recipient and template data
 */
public void sendNewEmailType(NotificationContext context) {
    enqueueNotification(EmailTemplateType.NEW_EMAIL_TYPE, context);
}
```

### Step 6: Create Feature Flag

Add the feature flag to your database or configuration:

```sql
INSERT INTO feature_flag (flag_key, enabled, config, owner, created_at, updated_at)
VALUES ('notifications.[category].[type]', true, '{}', 'admin@villagecompute.com', NOW(), NOW());
```

### Step 7: Write Tests

Create tests in `NotificationServiceTest.java`:

```java
@Test
void testSendNewEmailType_RendersTemplateWithData() {
    // Arrange
    NotificationContext context = NotificationContext.builder()
        .tenantId(tenantId)
        .customerId(customerId)
        .customerName("Test User")
        .customerEmail("test@example.com")
        .locale("en")
        .templateData(Map.of("key1", "value1", "key2", "value2"))
        .build();

    // Act
    notificationService.sendNewEmailType(context);
    notificationJobProcessor.processAllPending();

    // Assert
    List<Mail> sent = mailbox.getMessagesSentTo("test@example.com");
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).getHtml().contains("expected content"));
}
```

---

## Template Best Practices

### 1. Inline CSS

Always use inline CSS for maximum email client compatibility:

```html
<table style="width: 100%; border-collapse: collapse;">
    <tr>
        <td style="padding: 12px; border-bottom: 1px solid #e5e7eb;">Content</td>
    </tr>
</table>
```

### 2. Responsive Design

Use max-width containers for mobile compatibility:

```html
<div style="max-width: 600px; margin: 0 auto;">
    <!-- Email content -->
</div>
```

### 3. Theme Colors

Stick to the platform color scheme:

- **Primary Blue**: `#3b82f6`
- **Success Green**: `#10b981`
- **Error Red**: `#dc2626`
- **Text Dark**: `#111827`
- **Text Medium**: `#374151`
- **Text Light**: `#6b7280`
- **Border**: `#e5e7eb`
- **Background**: `#f9fafb`

### 4. Accessible Content

- Use semantic HTML (`<h1>`, `<h2>`, `<p>`, `<table>`)
- Include `alt` text for images
- Ensure sufficient color contrast (WCAG AA minimum)
- Use descriptive link text (avoid "click here")

### 5. Testing Across Clients

Test templates in major email clients:
- Gmail (web, iOS, Android)
- Outlook (desktop, web)
- Apple Mail
- Yahoo Mail

Use tools like [Litmus](https://litmus.com/) or [Email on Acid](https://www.emailonacid.com/) for cross-client testing.

---

## Testing Templates

### Local Testing with Mailhog

Development environment uses Mailhog to capture all emails locally.

1. **Start Mailhog**:
   ```bash
   docker-compose up -d
   ```

2. **View emails**: Open [http://localhost:8025](http://localhost:8025)

3. **Trigger notification** (via REST API or admin UI):
   ```java
   NotificationContext ctx = NotificationContext.builder()
       .tenantId(tenantId)
       .customerId(customerId)
       .customerName("Test User")
       .customerEmail("test@example.com")
       .locale("en")
       .templateData(sampleData)
       .build();

   notificationService.sendOrderConfirmation(ctx);
   ```

### Integration Tests

Run notification integration tests:

```bash
./mvnw test -Dtest=NotificationServiceTest
./mvnw test -Dtest=OrderNotificationServiceTest
```

### Manual Template Preview

To preview a template without sending:

```java
@Inject
@Location("email/order/order-confirmation.html")
Template orderConfirmationTemplate;

public String previewTemplate(Map<String, Object> data) {
    Map<String, String> messages = localizationService.loadMessages("en");
    data.put("msg", messages);
    data.put("locale", "en");
    return orderConfirmationTemplate.data(data).render();
}
```

---

## Domain Filtering

### Purpose

Domain filtering prevents accidental email delivery to real customers in non-production environments (staging).

### Configuration

**Development**: All emails captured by Mailhog (localhost:1025)

**Staging**: Domain filtering **ENABLED**
```properties
%staging.notifications.domain-filter.enabled=true
%staging.notifications.domain-filter.allowed=villagecompute.com,example.com
```

**Production**: Domain filtering **DISABLED**
```properties
%prod.notifications.domain-filter.enabled=false
```

### Behavior

When domain filtering is enabled:

- ✅ **Allowed**: `user@villagecompute.com`, `test@example.com`
- ❌ **Blocked**: `customer@gmail.com`, `user@yahoo.com`, `real@customer.com`

Blocked emails are **logged** but **not sent**, preventing accidental customer notifications during testing.

### Testing Domain Filtering

Run domain filter tests:

```bash
./mvnw test -Dtest=DomainFilterServiceTest
```

Verify staging behavior:
```bash
./mvnw test -Dtest=DomainFilterServiceTest$StagingDomainFilterTest
```

---

## Troubleshooting

### Email Not Sending

1. **Check feature flag**: Ensure notification type is enabled
2. **Verify Mailhog**: Confirm SMTP server is running (dev)
3. **Check logs**: Look for `NotificationService` log entries
4. **Domain filter**: Verify recipient domain is allowed (staging)

### Template Rendering Errors

1. **Missing message keys**: Check `messages.properties` for all required keys
2. **Qute syntax errors**: Validate template syntax (e.g., `{variable}` not `{{variable}}`)
3. **Null data**: Ensure all required `templateData` fields are provided

### Localization Issues

1. **Fallback to English**: Verify Spanish keys exist in `messages_es.properties`
2. **Key mismatch**: Ensure key names match exactly (case-sensitive)
3. **Special characters**: Use UTF-8 encoding for accented characters

---

## Reference

- **Qute Documentation**: https://quarkus.io/guides/qute
- **Quarkus Mailer**: https://quarkus.io/guides/mailer
- **Email Best Practices**: https://www.campaignmonitor.com/dev-resources/guides/coding/
- **Task I4.T5**: Order email templates and localization implementation
- **Task I3.T5**: Consignment email templates implementation

---

**Last Updated**: 2026-01-10
**Maintainers**: Platform Team (platform@villagecompute.com)
