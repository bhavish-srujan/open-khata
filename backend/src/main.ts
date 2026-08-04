import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  // Listen on all interfaces so physical devices on the same WiFi can connect
  await app.listen(process.env.PORT ?? 3000, '0.0.0.0');
  console.log(`🚀 Server running on http://0.0.0.0:${process.env.PORT ?? 3000}`);
  
  // Self-ping to prevent Render sleep (runs every 14 minutes)
  const renderUrl = 'https://khata-book-zirg.onrender.com';
  setInterval(() => {
    fetch(renderUrl)
      .then(res => console.log(`Self-ping successful: ${res.status}`))
      .catch(err => console.error('Self-ping failed:', err.message));
  }, 14 * 60 * 1000);
}
bootstrap();
