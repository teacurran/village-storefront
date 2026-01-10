#!/usr/bin/env node
/**
 * USPS Mock API Server
 * Simulates USPS Web Tools API for local development and testing
 *
 * Supported endpoints:
 * - POST /ShippingAPI.dll?API=RateV4 - Calculate shipping rates
 * - POST /ShippingAPI.dll?API=IntlRateV2 - International rates
 * - POST /ShippingAPI.dll?API=Verify - Address verification
 * - POST /ShippingAPI.dll?API=TrackV2 - Track packages
 *
 * Port: 9100 (configurable via USPS_MOCK_PORT)
 */

const http = require('http');
const url = require('url');
const querystring = require('querystring');

const PORT = process.env.USPS_MOCK_PORT || 9100;

// Mock response templates
const RATE_RESPONSE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<RateV4Response>
  <Package ID="1">
    <ZipOrigination>94103</ZipOrigination>
    <ZipDestination>{ZIP_DEST}</ZipDestination>
    <Pounds>{POUNDS}</Pounds>
    <Ounces>{OUNCES}</Ounces>
    <Container>VARIABLE</Container>
    <Size>REGULAR</Size>
    <Zone>4</Zone>
    <Postage CLASSID="1">
      <MailService>Priority Mail 2-Day</MailService>
      <Rate>8.95</Rate>
      <CommercialRate>7.45</CommercialRate>
      <SpecialServices/>
    </Postage>
    <Postage CLASSID="4">
      <MailService>Priority Mail Express 1-Day</MailService>
      <Rate>27.50</Rate>
      <CommercialRate>25.80</CommercialRate>
      <SpecialServices/>
    </Postage>
    <Postage CLASSID="6">
      <MailService>Media Mail Parcel</MailService>
      <Rate>3.89</Rate>
      <SpecialServices/>
    </Postage>
  </Package>
</RateV4Response>`;

const VERIFY_RESPONSE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<AddressValidateResponse>
  <Address ID="0">
    <Address2>123 MAIN ST</Address2>
    <City>SAN FRANCISCO</City>
    <State>CA</State>
    <Zip5>94103</Zip5>
    <Zip4>1234</Zip4>
    <DeliveryPoint>23</DeliveryPoint>
    <CarrierRoute>C001</CarrierRoute>
    <ReturnText>Default address: The address you entered was found but more information is needed</ReturnText>
  </Address>
</AddressValidateResponse>`;

const TRACK_RESPONSE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<TrackResponse>
  <TrackInfo ID="{TRACKING_NUMBER}">
    <TrackSummary>Your item was delivered at 10:32 am on January 8, 2026 in SAN FRANCISCO, CA 94103.</TrackSummary>
    <TrackDetail>January 8, 2026, 10:32 am DELIVERED SAN FRANCISCO, CA 94103</TrackDetail>
    <TrackDetail>January 8, 2026, 9:15 am Out for Delivery SAN FRANCISCO, CA 94103</TrackDetail>
    <TrackDetail>January 8, 2026, 6:00 am Arrived at Post Office SAN FRANCISCO, CA 94103</TrackDetail>
    <TrackDetail>January 7, 2026, 11:00 pm Departed USPS Facility OAKLAND, CA 94621</TrackDetail>
    <TrackDetail>January 7, 2026, 8:30 pm Arrived at USPS Facility OAKLAND, CA 94621</TrackDetail>
  </TrackInfo>
</TrackResponse>`;

const ERROR_RESPONSE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Number>-2147219400</Number>
  <Description>Invalid API request. Please check your XML for format errors.</Description>
  <Source>USPS Web Tools API</Source>
</Error>`;

const server = http.createServer((req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;
  const query = parsedUrl.query;

  // Set CORS headers for browser-based requests
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  // Log request
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);

  // Handle different API endpoints
  if (pathname === '/ShippingAPI.dll') {
    const apiType = query.API;

    let body = '';
    req.on('data', chunk => {
      body += chunk.toString();
    });

    req.on('end', () => {
      console.log(`API Type: ${apiType}`);
      console.log(`Request Body: ${body.substring(0, 200)}${body.length > 200 ? '...' : ''}`);

      res.setHeader('Content-Type', 'application/xml');

      switch (apiType) {
        case 'RateV4':
        case 'Rate':
          // Extract zip and weight from XML if possible
          const zipMatch = body.match(/<Zip>(\d+)<\/Zip>/);
          const poundsMatch = body.match(/<Pounds>(\d+)<\/Pounds>/);
          const ouncesMatch = body.match(/<Ounces>(\d+)<\/Ounces>/);

          const response = RATE_RESPONSE_XML
            .replace('{ZIP_DEST}', zipMatch ? zipMatch[1] : '94103')
            .replace('{POUNDS}', poundsMatch ? poundsMatch[1] : '2')
            .replace('{OUNCES}', ouncesMatch ? ouncesMatch[1] : '0');

          res.writeHead(200);
          res.end(response);
          break;

        case 'IntlRateV2':
          // Simple international rate response
          res.writeHead(200);
          res.end(`<?xml version="1.0" encoding="UTF-8"?>
<IntlRateV2Response>
  <Package ID="1">
    <Pounds>2</Pounds>
    <Ounces>0</Ounces>
    <MailType>Package</MailType>
    <Country>CANADA</Country>
    <Service ID="1">
      <SvcDescription>Priority Mail Express International</SvcDescription>
      <Rate>47.95</Rate>
      <SvcCommitments>3 - 5 Days</SvcCommitments>
    </Service>
    <Service ID="2">
      <SvcDescription>Priority Mail International</SvcDescription>
      <Rate>36.85</Rate>
      <SvcCommitments>6 - 10 Days</SvcCommitments>
    </Service>
  </Package>
</IntlRateV2Response>`);
          break;

        case 'Verify':
        case 'AddressValidate':
          res.writeHead(200);
          res.end(VERIFY_RESPONSE_XML);
          break;

        case 'TrackV2':
        case 'Track':
          const trackMatch = body.match(/<TrackID>([^<]+)<\/TrackID>/);
          const trackResponse = TRACK_RESPONSE_XML.replace(
            '{TRACKING_NUMBER}',
            trackMatch ? trackMatch[1] : '9400100000000000000000'
          );
          res.writeHead(200);
          res.end(trackResponse);
          break;

        default:
          res.writeHead(400);
          res.end(ERROR_RESPONSE_XML);
      }
    });
  } else if (pathname === '/health') {
    // Health check endpoint
    res.setHeader('Content-Type', 'application/json');
    res.writeHead(200);
    res.end(JSON.stringify({ status: 'ok', service: 'usps-mock', timestamp: new Date().toISOString() }));
  } else {
    res.writeHead(404);
    res.end('Not Found');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`USPS Mock API Server running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/health`);
  console.log(`Rate API: http://localhost:${PORT}/ShippingAPI.dll?API=RateV4`);
  console.log('Ready to accept requests...');
});
