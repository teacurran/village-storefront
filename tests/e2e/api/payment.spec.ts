import { test, expect, request, APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

type StripeEvent = {
  id: string;
  type: string;
  created: number;
  data: {
    object: Record<string, unknown>;
  };
};

const tenant = tenants.tenantA;
const baseURL = getTenantBaseUrl(tenant);

const buildStripeEvent = (type: string, overrides: Record<string, unknown> = {}): StripeEvent => {
  const eventId = `evt_${randomUUID().replace(/-/g, '')}`;
  const payload: StripeEvent = {
    id: eventId,
    type,
    created: Math.floor(Date.now() / 1000),
    data: {
      object: {
        id: type.startsWith('charge.') ? `ch_${randomUUID()}` : `pi_${randomUUID()}`,
        amount: 1999,
        amount_received: 1999,
        currency: 'usd',
        payment_intent: `pi_${randomUUID()}`,
        metadata: {
          orderId: randomUUID(),
        },
      },
    },
  };

  payload.data.object = {
    ...payload.data.object,
    ...overrides,
  };

  return payload;
};

const stripeHeaders = (eventId: string) => ({
  'Stripe-Signature': 't=1234567890,v1=testsignature',
  'Stripe-Event-Id': eventId,
});

const withApiContext = async (
  fn: (apiContext: APIRequestContext) => Promise<void>,
): Promise<void> => {
  const apiContext = await request.newContext();
  try {
    await fn(apiContext);
  } finally {
    await apiContext.dispose();
  }
};

test.describe('Payments API :: Stripe webhooks', () => {
  test('accepts webhook payload replay', async () => {
    await withApiContext(async (apiContext) => {
      const event = buildStripeEvent('payment_intent.succeeded');
      const response = await apiContext.post(`${baseURL}/api/webhooks/payments/stripe`, {
        data: event,
        headers: stripeHeaders(event.id),
      });

      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(body.received).toBeTruthy();
      expect(body.alreadyProcessed ?? false).toBeFalsy();
      expect(body.eventId).toBeTruthy();
    });
  });

  test('treats duplicate events as already processed', async () => {
    await withApiContext(async (apiContext) => {
      const event = buildStripeEvent('payment_intent.succeeded');

      const firstResponse = await apiContext.post(`${baseURL}/api/webhooks/payments/stripe`, {
        data: event,
        headers: stripeHeaders(event.id),
      });
      expect(firstResponse.status()).toBe(200);
      const firstBody = await firstResponse.json();
      expect(firstBody.alreadyProcessed ?? false).toBeFalsy();

      const secondResponse = await apiContext.post(`${baseURL}/api/webhooks/payments/stripe`, {
        data: event,
        headers: stripeHeaders(event.id),
      });
      expect(secondResponse.status()).toBe(200);
      const secondBody = await secondResponse.json();
      expect(secondBody.alreadyProcessed).toBeTruthy();
    });
  });

  test('requires Stripe signature header', async () => {
    await withApiContext(async (apiContext) => {
      const event = buildStripeEvent('payment_intent.succeeded');
      const response = await apiContext.post(`${baseURL}/api/webhooks/payments/stripe`, {
        data: event,
      });

      expect(response.status()).toBe(400);
      const body = await response.json();
      expect(body.error).toContain('Stripe-Signature');
    });
  });
});
