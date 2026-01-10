FROM node:20-alpine

WORKDIR /app

# Copy mock server scripts
COPY *.js ./

# Make scripts executable
RUN chmod +x *.js

# Expose ports for all three services
EXPOSE 9100 9101 9102

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "require('http').get('http://localhost:${PORT}/health', (r) => { process.exit(r.statusCode === 200 ? 0 : 1); });"

# Default to USPS mock, but can be overridden
CMD ["node", "usps-mock.js"]
