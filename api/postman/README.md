# Postman Collection

This directory contains Postman collections for the Village Storefront API.

## Generate Collection from OpenAPI Spec

The Postman collection is auto-generated from the OpenAPI specification using the `openapi-to-postmanv2` converter.

### Prerequisites

```bash
npm install -g openapi-to-postmanv2
```

### Generate Collection

```bash
# From the project root
openapi2postmanv2 -s api/v1/openapi.yaml -o api/postman/village-storefront-api.postman_collection.json -p
```

### Import to Postman

1. Open Postman Desktop or Web
2. Click **Import** button
3. Select `api/postman/village-storefront-api.postman_collection.json`
4. Configure environment variables:
   - `BASE_URL`: `https://api.villagecompute.com/v1` (production) or `http://localhost:8080/api/v1` (local)
   - `CLIENT_ID`: Your OAuth client ID
   - `CLIENT_SECRET`: Your OAuth client secret
   - `TENANT_ID`: Your tenant identifier

### Example Environment Setup

Create a Postman environment with these variables:

```json
{
  "name": "Village Storefront - Local",
  "values": [
    { "key": "BASE_URL", "value": "http://localhost:8080/api/v1", "enabled": true },
    { "key": "AUTH_URL", "value": "http://localhost:8080/oauth/token", "enabled": true },
    { "key": "CLIENT_ID", "value": "your-client-id", "enabled": true },
    { "key": "CLIENT_SECRET", "value": "your-client-secret", "enabled": true },
    { "key": "TENANT_ID", "value": "techgadgets", "enabled": true },
    { "key": "ACCESS_TOKEN", "value": "", "enabled": true }
  ]
}
```

### Pre-Request Script

Add this pre-request script to automatically obtain access tokens:

```javascript
// Automatically obtain access token before each request
const getToken = {
  url: pm.environment.get("AUTH_URL"),
  method: 'POST',
  header: 'Content-Type:application/x-www-form-urlencoded',
  body: {
    mode: 'urlencoded',
    urlencoded: [
      { key: 'grant_type', value: 'client_credentials' },
      { key: 'client_id', value: pm.environment.get("CLIENT_ID") },
      { key: 'client_secret', value: pm.environment.get("CLIENT_SECRET") },
      { key: 'scope', value: 'catalog:read checkout:write' }
    ]
  }
};

// Only fetch new token if current one is missing or expired
if (!pm.environment.get("ACCESS_TOKEN")) {
  pm.sendRequest(getToken, (err, res) => {
    if (err) {
      console.error(err);
    } else {
      const token = res.json().access_token;
      pm.environment.set("ACCESS_TOKEN", token);
    }
  });
}
```

## Maintenance

The Postman collection should be regenerated whenever the OpenAPI spec is updated:

```bash
# 1. Update api/v1/openapi.yaml
# 2. Validate changes
npm run lint:openapi

# 3. Regenerate Postman collection
openapi2postmanv2 -s api/v1/openapi.yaml -o api/postman/village-storefront-api.postman_collection.json -p

# 4. Test collection in Postman
# 5. Commit updated collection
git add api/postman/village-storefront-api.postman_collection.json
git commit -m "chore: regenerate Postman collection from updated OpenAPI spec"
```

## Alternative: Bruno

For developers preferring open-source alternatives to Postman, consider [Bruno](https://www.usebruno.com/):

```bash
# Generate Bruno collection (requires bruno-cli)
npm install -g @usebruno/cli
bruno import openapi api/v1/openapi.yaml -o api/bruno/
```

---

**Last Updated:** 2026-01-18
