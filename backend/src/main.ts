import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  // Listen on all interfaces so physical devices on the same WiFi can connect
  await app.listen(process.env.PORT ?? 3000, '0.0.0.0');
  console.log(`🚀 Server running on http://0.0.0.0:${process.env.PORT ?? 3000}`);
  console.log(`📱 From physical device use: http://10.237.29.153:${process.env.PORT ?? 3000}`);
}
bootstrap();
