#!/usr/bin/env node
/**
 * FedEx Mock API Server
 * Simulates FedEx API for local development and testing
 *
 * Supported endpoints:
 * - POST /rate/v1/rates/quotes - Calculate shipping rates
 * - POST /ship/v1/shipments - Create shipment
 * - POST /track/v1/trackingnumbers - Track packages
 * - POST /country/v1/postal/validate - Validate address
 *
 * Port: 9102 (configurable via FEDEX_MOCK_PORT)
 */

const http = require('http');
const url = require('url');

const PORT = process.env.FEDEX_MOCK_PORT || 9102;

// Mock response templates
const RATE_RESPONSE = {
  transactionId: 'mock-transaction-' + Date.now(),
  output: {
    rateReplyDetails: [
      {
        serviceType: 'FEDEX_GROUND',
        serviceName: 'FedEx Ground',
        packagingType: 'YOUR_PACKAGING',
        ratedShipmentDetails: [
          {
            rateType: 'PAYOR_ACCOUNT_PACKAGE',
            ratedWeightMethod: 'ACTUAL',
            totalDiscounts: 0,
            totalBaseCharge: 11.50,
            totalNetCharge: 11.50,
            totalNetFedExCharge: 11.50,
            shipmentRateDetail: {
              rateZone: '4',
              dimDivisor: 139,
              fuelSurchargePercent: 0,
              totalSurcharges: 0,
              totalFreightDiscount: 0,
              currency: 'USD'
            }
          }
        ],
        commit: {
          dateDetail: {
            dayFormat: 'WEEKDAY',
            dayCodes: ['FRI']
          }
        },
        operationalDetail: {
          originLocationIds: ['OAKL'],
          deliveryDate: '2026-01-10',
          deliveryDay: 'FRI',
          ineligibleForMoneyBackGuarantee: false,
          astraDescription: 'FEDEX GROUND',
          originServiceAreas: ['AM'],
          destinationServiceAreas: ['A1'],
          transitTime: 'TWO_DAYS'
        }
      },
      {
        serviceType: 'FEDEX_2_DAY',
        serviceName: 'FedEx 2Day',
        packagingType: 'YOUR_PACKAGING',
        ratedShipmentDetails: [
          {
            rateType: 'PAYOR_ACCOUNT_PACKAGE',
            ratedWeightMethod: 'ACTUAL',
            totalDiscounts: 0,
            totalBaseCharge: 27.85,
            totalNetCharge: 27.85,
            totalNetFedExCharge: 27.85,
            shipmentRateDetail: {
              rateZone: '4',
              dimDivisor: 139,
              fuelSurchargePercent: 0,
              totalSurcharges: 0,
              totalFreightDiscount: 0,
              currency: 'USD'
            }
          }
        ],
        commit: {
          dateDetail: {
            dayFormat: 'WEEKDAY',
            dayCodes: ['THU']
          }
        },
        operationalDetail: {
          originLocationIds: ['OAKL'],
          deliveryDate: '2026-01-09',
          deliveryDay: 'THU',
          ineligibleForMoneyBackGuarantee: false,
          astraDescription: 'FEDEX 2DAY',
          originServiceAreas: ['AM'],
          destinationServiceAreas: ['A1'],
          transitTime: 'ONE_DAY'
        }
      },
      {
        serviceType: 'STANDARD_OVERNIGHT',
        serviceName: 'FedEx Standard Overnight',
        packagingType: 'YOUR_PACKAGING',
        ratedShipmentDetails: [
          {
            rateType: 'PAYOR_ACCOUNT_PACKAGE',
            ratedWeightMethod: 'ACTUAL',
            totalDiscounts: 0,
            totalBaseCharge: 52.30,
            totalNetCharge: 52.30,
            totalNetFedExCharge: 52.30,
            shipmentRateDetail: {
              rateZone: '4',
              dimDivisor: 139,
              fuelSurchargePercent: 0,
              totalSurcharges: 0,
              totalFreightDiscount: 0,
              currency: 'USD'
            }
          }
        ],
        commit: {
          dateDetail: {
            dayFormat: 'WEEKDAY',
            dayCodes: ['WED']
          }
        },
        operationalDetail: {
          originLocationIds: ['OAKL'],
          deliveryDate: '2026-01-08',
          deliveryDay: 'WED',
          ineligibleForMoneyBackGuarantee: false,
          astraDescription: 'STANDARD OVERNIGHT',
          originServiceAreas: ['AM'],
          destinationServiceAreas: ['A1'],
          transitTime: 'OVERNIGHT'
        }
      }
    ],
    quoteDate: '2026-01-08',
    encoded: false
  }
};

const TRACKING_RESPONSE = {
  transactionId: 'mock-transaction-' + Date.now(),
  output: {
    completeTrackResults: [
      {
        trackingNumber: '123456789012',
        trackResults: [
          {
            trackingNumberInfo: {
              trackingNumber: '123456789012',
              carrierCode: 'FDXG',
              trackingNumberUniqueId: 'mock-unique-id'
            },
            additionalTrackingInfo: {
              nickname: 'Test Package',
              packageIdentifiers: [
                {
                  type: 'TRACKING_NUMBER_OR_DOORTAG',
                  value: '123456789012'
                }
              ],
              hasAssociatedShipments: false
            },
            shipperInformation: {
              address: {
                city: 'OAKLAND',
                stateOrProvinceCode: 'CA',
                postalCode: '94621',
                countryCode: 'US'
              }
            },
            recipientInformation: {
              address: {
                city: 'SAN FRANCISCO',
                stateOrProvinceCode: 'CA',
                postalCode: '94103',
                countryCode: 'US'
              }
            },
            latestStatusDetail: {
              scanLocation: {
                city: 'SAN FRANCISCO',
                stateOrProvinceCode: 'CA',
                countryCode: 'US'
              },
              code: 'DL',
              derivedCode: 'DL',
              statusByLocale: 'Delivered',
              description: 'Delivered',
              ancillaryDetails: [
                {
                  reason: 'Left at front door. Signature Service not requested.',
                  reasonDescription: 'Left at front door. Signature Service not requested.'
                }
              ]
            },
            dateAndTimes: [
              {
                type: 'ACTUAL_DELIVERY',
                dateTime: '2026-01-08T10:32:00-08:00'
              },
              {
                type: 'ESTIMATED_DELIVERY',
                dateTime: '2026-01-08T20:00:00-08:00'
              }
            ],
            availableImages: [],
            specialHandlings: [],
            packageDetails: {
              physicalPackagingType: 'YOUR_PACKAGING',
              packagingDescription: {
                type: 'YOUR_PACKAGING',
                description: 'Package'
              },
              sequenceNumber: '1',
              count: '1',
              weightAndDimensions: {
                weight: [
                  {
                    unit: 'LB',
                    value: '5.0'
                  }
                ]
              }
            },
            scanEvents: [
              {
                date: '2026-01-08T10:32:00-08:00',
                eventType: 'DL',
                eventDescription: 'Delivered',
                scanLocation: {
                  city: 'SAN FRANCISCO',
                  stateOrProvinceCode: 'CA',
                  postalCode: '94103',
                  countryCode: 'US'
                },
                derivedStatus: 'Delivered'
              },
              {
                date: '2026-01-08T09:15:00-08:00',
                eventType: 'OD',
                eventDescription: 'On FedEx vehicle for delivery',
                scanLocation: {
                  city: 'SAN FRANCISCO',
                  stateOrProvinceCode: 'CA',
                  countryCode: 'US'
                },
                derivedStatus: 'In transit'
              },
              {
                date: '2026-01-08T06:00:00-08:00',
                eventType: 'AR',
                eventDescription: 'At local FedEx facility',
                scanLocation: {
                  city: 'SAN FRANCISCO',
                  stateOrProvinceCode: 'CA',
                  countryCode: 'US'
                },
                derivedStatus: 'In transit'
              },
              {
                date: '2026-01-07T23:00:00-08:00',
                eventType: 'DP',
                eventDescription: 'Departed FedEx location',
                scanLocation: {
                  city: 'OAKLAND',
                  stateOrProvinceCode: 'CA',
                  countryCode: 'US'
                },
                derivedStatus: 'In transit'
              }
            ],
            availableNotifications: [],
            deliveryDetails: {
              deliveryAttempts: '0',
              deliveryOptionEligibilityDetails: []
            },
            originLocation: {
              locationContactAndAddress: {
                address: {
                  city: 'OAKLAND',
                  stateOrProvinceCode: 'CA',
                  countryCode: 'US'
                }
              }
            },
            lastUpdatedDestinationAddress: {
              city: 'SAN FRANCISCO',
              stateOrProvinceCode: 'CA',
              postalCode: '94103',
              countryCode: 'US'
            }
          }
        ]
      }
    ]
  }
};

const ADDRESS_VALIDATION_RESPONSE = {
  transactionId: 'mock-transaction-' + Date.now(),
  output: {
    resolvedAddresses: [
      {
        streetLinesToken: '123 MAIN ST',
        city: 'SAN FRANCISCO',
        stateOrProvinceCode: 'CA',
        postalCode: '94103',
        countryCode: 'US',
        residential: false,
        classification: 'BUSINESS',
        attributes: {
          DPV: 'CONFIRMED'
        }
      }
    ]
  }
};

const ERROR_RESPONSE = {
  transactionId: 'mock-error-' + Date.now(),
  errors: [
    {
      code: 'INVALID.INPUT.EXCEPTION',
      message: 'Invalid request'
    }
  ]
};

const server = http.createServer((req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  // Set CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-locale');
  res.setHeader('Content-Type', 'application/json');

  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  // Log request
  console.log(`[${new Date().toISOString()}] ${req.method} ${pathname}`);

  let body = '';
  req.on('data', chunk => {
    body += chunk.toString();
  });

  req.on('end', () => {
    if (body) {
      console.log(`Request Body: ${body.substring(0, 200)}${body.length > 200 ? '...' : ''}`);
    }

    // Route handling
    if (pathname.startsWith('/rate/')) {
      // Rating API
      res.writeHead(200);
      res.end(JSON.stringify(RATE_RESPONSE));
    } else if (pathname.startsWith('/track/')) {
      // Tracking API
      let trackingResp = JSON.parse(JSON.stringify(TRACKING_RESPONSE));

      // Try to extract tracking number from request body
      if (body) {
        try {
          const reqBody = JSON.parse(body);
          if (reqBody.trackingInfo && reqBody.trackingInfo[0] && reqBody.trackingInfo[0].trackingNumberInfo) {
            const trackingNumber = reqBody.trackingInfo[0].trackingNumberInfo.trackingNumber;
            trackingResp.output.completeTrackResults[0].trackingNumber = trackingNumber;
            trackingResp.output.completeTrackResults[0].trackResults[0].trackingNumberInfo.trackingNumber = trackingNumber;
          }
        } catch (e) {
          // Ignore parse errors, use default tracking number
        }
      }

      res.writeHead(200);
      res.end(JSON.stringify(trackingResp));
    } else if (pathname.startsWith('/country/') || pathname.startsWith('/address/')) {
      // Address validation
      res.writeHead(200);
      res.end(JSON.stringify(ADDRESS_VALIDATION_RESPONSE));
    } else if (pathname.startsWith('/ship/')) {
      // Shipment creation
      res.writeHead(200);
      res.end(JSON.stringify({
        transactionId: 'mock-transaction-' + Date.now(),
        output: {
          transactionShipments: [
            {
              serviceType: 'FEDEX_GROUND',
              shipDatestamp: '2026-01-08',
              masterTrackingNumber: '123456789012',
              completedPackageDetails: [
                {
                  sequenceNumber: '1',
                  trackingNumber: '123456789012',
                  label: {
                    imageType: 'PNG',
                    resolution: 203,
                    copiesToPrint: 1,
                    encodedLabel: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
                  }
                }
              ],
              shipmentRating: {
                actualRateType: 'PAYOR_ACCOUNT_PACKAGE',
                shipmentRateDetails: [
                  {
                    rateZone: '4',
                    ratedWeightMethod: 'ACTUAL',
                    totalNetCharge: 11.50,
                    currency: 'USD'
                  }
                ]
              }
            }
          ]
        }
      }));
    } else if (pathname === '/health') {
      // Health check
      res.writeHead(200);
      res.end(JSON.stringify({ status: 'ok', service: 'fedex-mock', timestamp: new Date().toISOString() }));
    } else {
      res.writeHead(404);
      res.end(JSON.stringify(ERROR_RESPONSE));
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`FedEx Mock API Server running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/health`);
  console.log(`Rate API: http://localhost:${PORT}/rate/v1/rates/quotes`);
  console.log(`Track API: http://localhost:${PORT}/track/v1/trackingnumbers`);
  console.log('Ready to accept requests...');
});
