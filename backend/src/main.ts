import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { ValidationPipe } from '@nestjs/common';
import { NestExpressApplication } from '@nestjs/platform-express';
import { auditContextMiddleware } from './audit-logs/audit-context';
import { UPLOADS_ROOT } from './uploads/uploads.config';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);

  // Must be first: opens the AsyncLocalStorage scope that JwtAuthGuard writes
  // the acting user into and AuditLogSubscriber later reads.
  app.use(auditContextMiddleware);

  app.enableCors();
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }));
  
  // Serve uploaded files statically, from the same constant the upload
  // controller writes to, so the two cannot drift apart again.
  app.useStaticAssets(UPLOADS_ROOT, {
    prefix: '/uploads/',
  });

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
