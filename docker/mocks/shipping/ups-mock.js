#!/usr/bin/env node
/**
 * UPS Mock API Server
 * Simulates UPS API for local development and testing
 *
 * Supported endpoints:
 * - POST /api/rating/v1/Rate - Calculate shipping rates
 * - POST /api/shipments/v1/ship - Create shipment
 * - GET /api/track/v1/details/{trackingNumber} - Track packages
 * - POST /api/addressvalidation/v1/1 - Validate address
 *
 * Port: 9101 (configurable via UPS_MOCK_PORT)
 */

const http = require('http');
const url = require('url');

const PORT = process.env.UPS_MOCK_PORT || 9101;

// Mock response templates
const RATE_RESPONSE = {
  RateResponse: {
    Response: {
      ResponseStatus: {
        Code: '1',
        Description: 'Success'
      },
      TransactionReference: {
        CustomerContext: 'Rating Request'
      }
    },
    RatedShipment: [
      {
        Service: {
          Code: '03',
          Description: 'UPS Ground'
        },
        RatedShipmentAlert: [],
        BillingWeight: {
          UnitOfMeasurement: {
            Code: 'LBS'
          },
          Weight: '5.0'
        },
        TransportationCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '12.45'
        },
        ServiceOptionsCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '0.00'
        },
        TotalCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '12.45'
        },
        GuaranteedDelivery: {
          BusinessDaysInTransit: '3'
        },
        RatedPackage: [
          {
            Weight: '5.0'
          }
        ]
      },
      {
        Service: {
          Code: '02',
          Description: 'UPS 2nd Day Air'
        },
        RatedShipmentAlert: [],
        BillingWeight: {
          UnitOfMeasurement: {
            Code: 'LBS'
          },
          Weight: '5.0'
        },
        TransportationCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '28.90'
        },
        ServiceOptionsCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '0.00'
        },
        TotalCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '28.90'
        },
        GuaranteedDelivery: {
          BusinessDaysInTransit: '2'
        },
        RatedPackage: [
          {
            Weight: '5.0'
          }
        ]
      },
      {
        Service: {
          Code: '01',
          Description: 'UPS Next Day Air'
        },
        RatedShipmentAlert: [],
        BillingWeight: {
          UnitOfMeasurement: {
            Code: 'LBS'
          },
          Weight: '5.0'
        },
        TransportationCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '54.75'
        },
        ServiceOptionsCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '0.00'
        },
        TotalCharges: {
          CurrencyCode: 'USD',
          MonetaryValue: '54.75'
        },
        GuaranteedDelivery: {
          BusinessDaysInTransit: '1'
        },
        RatedPackage: [
          {
            Weight: '5.0'
          }
        ]
      }
    ]
  }
};

const TRACKING_RESPONSE = {
  trackResponse: {
    shipment: [
      {
        package: [
          {
            trackingNumber: '1Z999AA10123456784',
            deliveryDate: [
              {
                type: 'DEL',
                date: '20260108'
              }
            ],
            deliveryTime: {
              startTime: '103000',
              endTime: '143000',
              type: 'EDW'
            },
            activity: [
              {
                location: {
                  address: {
                    city: 'SAN FRANCISCO',
                    stateProvince: 'CA',
                    postalCode: '94103',
                    country: 'US'
                  }
                },
                status: {
                  type: 'D',
                  description: 'Delivered',
                  code: 'FS'
                },
                date: '20260108',
                time: '103200'
              },
              {
                location: {
                  address: {
                    city: 'SAN FRANCISCO',
                    stateProvince: 'CA',
                    country: 'US'
                  }
                },
                status: {
                  type: 'I',
                  description: 'Out For Delivery Today',
                  code: 'OT'
                },
                date: '20260108',
                time: '091500'
              },
              {
                location: {
                  address: {
                    city: 'OAKLAND',
                    stateProvince: 'CA',
                    country: 'US'
                  }
                },
                status: {
                  type: 'I',
                  description: 'Arrived at Facility',
                  code: 'AR'
                },
                date: '20260108',
                time: '060000'
              }
            ],
            currentStatus: {
              description: 'Delivered',
              simplifiedTextDescription: 'Delivered',
              statusCode: 'FS',
              type: 'D'
            }
          }
        ]
      }
    ]
  }
};

const ADDRESS_VALIDATION_RESPONSE = {
  AddressValidationResponse: {
    Response: {
      ResponseStatus: {
        Code: '1',
        Description: 'Success'
      }
    },
    AddressValidationResult: [
      {
        Rank: '1',
        Quality: '1.0',
        Address: {
          City: 'SAN FRANCISCO',
          StateProvinceCode: 'CA',
          PostalCode: '94103',
          PostalCodeExtendedLow: '1234',
          CountryCode: 'US'
        },
        PostalCodeLowEnd: '94103',
        PostalCodeHighEnd: '94103'
      }
    ]
  }
};

const ERROR_RESPONSE = {
  response: {
    errors: [
      {
        code: '9999999',
        message: 'Invalid request'
      }
    ]
  }
};

const server = http.createServer((req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  // Set CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, transId, transactionSrc');
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
    if (pathname.startsWith('/api/rating/')) {
      // Rating API
      res.writeHead(200);
      res.end(JSON.stringify(RATE_RESPONSE));
    } else if (pathname.startsWith('/api/track/')) {
      // Tracking API
      const trackingNumber = pathname.split('/').pop();
      const trackingResp = JSON.parse(JSON.stringify(TRACKING_RESPONSE));
      trackingResp.trackResponse.shipment[0].package[0].trackingNumber = trackingNumber;
      res.writeHead(200);
      res.end(JSON.stringify(trackingResp));
    } else if (pathname.startsWith('/api/addressvalidation/')) {
      // Address validation
      res.writeHead(200);
      res.end(JSON.stringify(ADDRESS_VALIDATION_RESPONSE));
    } else if (pathname.startsWith('/api/shipments/')) {
      // Shipment creation
      res.writeHead(200);
      res.end(JSON.stringify({
        ShipmentResponse: {
          Response: {
            ResponseStatus: {
              Code: '1',
              Description: 'Success'
            }
          },
          ShipmentResults: {
            ShipmentIdentificationNumber: '1Z999AA10123456784',
            ShipmentCharges: {
              TotalCharges: {
                CurrencyCode: 'USD',
                MonetaryValue: '12.45'
              }
            },
            PackageResults: [
              {
                TrackingNumber: '1Z999AA10123456784',
                ShippingLabel: {
                  ImageFormat: {
                    Code: 'GIF'
                  },
                  GraphicImage: 'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
                }
              }
            ]
          }
        }
      }));
    } else if (pathname === '/health') {
      // Health check
      res.writeHead(200);
      res.end(JSON.stringify({ status: 'ok', service: 'ups-mock', timestamp: new Date().toISOString() }));
    } else {
      res.writeHead(404);
      res.end(JSON.stringify(ERROR_RESPONSE));
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`UPS Mock API Server running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/health`);
  console.log(`Rate API: http://localhost:${PORT}/api/rating/v1/Rate`);
  console.log(`Track API: http://localhost:${PORT}/api/track/v1/details/{trackingNumber}`);
  console.log('Ready to accept requests...');
});
