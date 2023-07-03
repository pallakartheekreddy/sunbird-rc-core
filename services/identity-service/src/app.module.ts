import { Module } from '@nestjs/common';
import { AppController } from './app.controller';
import { PrismaService } from './utils/prisma.service';
import { DidService } from './did/did.service';
import { DidController } from './did/did.controller';
import { DidModule } from './did/did.module';
import { HttpModule } from '@nestjs/axios';
import { ConfigModule } from '@nestjs/config';
import { VcModule } from './vc/vc.module';
import { VaultService } from './did/vault.service';
import { APP_GUARD } from '@nestjs/core';
import { AuthGuard } from './auth/auth.guard';
import { TerminusModule } from '@nestjs/terminus';
import { PrismaHealthIndicator } from './utils/prisma.health';

@Module({
  imports: [
    DidModule,
    VcModule,
    HttpModule,
    ConfigModule.forRoot({
      isGlobal: true,
    }),
    TerminusModule
  ],
  controllers: [AppController, DidController],
  providers: [
    PrismaService, DidService, VaultService,
    {
      provide: APP_GUARD,
      useClass: AuthGuard,
    },
    PrismaHealthIndicator
  ],
})
export class AppModule {}
