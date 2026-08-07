import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { ValidationPipe } from '@nestjs/common';
import { NestExpressApplication } from '@nestjs/platform-express';
import { auditContextMiddleware } from './audit-logs/audit-context';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);

  // Must be first: opens the AsyncLocalStorage scope that JwtAuthGuard writes
  // the acting user into and AuditLogSubscriber later reads.
  app.use(auditContextMiddleware);

  // The Android client sends no Origin header, so it is unaffected by CORS.
  // A wildcard only widens who can drive this API from a browser, so origins
  // are opt-in: set CORS_ORIGINS to a comma-separated list to allow any.
  const allowedOrigins = (process.env.CORS_ORIGINS ?? '')
    .split(',')
    .map((o) => o.trim())
    .filter(Boolean);
  app.enableCors({
    origin: allowedOrigins.length > 0 ? allowedOrigins : false,
    credentials: true,
  });
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }));
  
  // No static handler for /uploads/**. Receipts are financial documents and
  // were previously world-readable to anyone holding a URL; they are now served
  // only by GET /uploads/receipts/:id, which requires a token and checks that
  // the receipt belongs to the caller's business.

  // Listen on all interfaces so physical devices on the same WiFi can connect
  await app.listen(process.env.PORT ?? 3000, '0.0.0.0');
  console.log(`🚀 Server running on http://0.0.0.0:${process.env.PORT ?? 3000}`);
  
  const selfPingUrl = process.env.SELF_PING_URL;
  if (selfPingUrl) {
    setInterval(() => {
      fetch(selfPingUrl)
        .then(res => console.log(`Self-ping successful: ${res.status}`))
        .catch(err => console.error('Self-ping failed:', err.message));
    }, 14 * 60 * 1000);
  }
}
bootstrap();
