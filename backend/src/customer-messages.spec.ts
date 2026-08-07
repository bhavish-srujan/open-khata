import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test } from '@nestjs/testing';
import { LedgerService } from './ledger/ledger.service';
import { LedgerEntry } from './ledger/ledger-entry.entity';
import { BalanceHistory } from './ledger/balance-history.entity';
import { PartyService } from './party/party.service';
import { Party } from './party/party.entity';
import { Business } from './business/business.entity';
import { SmsService } from './notifications/sms/sms.service';

/**
 * What customers actually receive.
 *
 * Both messages interpolated the raw businessId, so a customer was told
 * "…3f1a9c22-0000-4000-8000-000000000001 gave you Rs 500". The transaction
 * message additionally reported Math.abs(balance) under the fixed label
 * "Current Balance", which tells someone holding credit that they owe that
 * amount — an invitation to pay twice.
 */
describe('customer-facing SMS content', () => {
  const BUSINESS_ID = '11111111-1111-4111-8111-111111111111';
  const PARTY_ID = '22222222-2222-4222-8222-222222222222';
  const BUSINESS_NAME = 'Rajesh Kirana Store';

  let sendSms: jest.Mock;

  /** The message body handed to the gateway. */
  const lastMessage = () => sendSms.mock.calls.at(-1)?.[1] as string;

  describe('transaction message', () => {
    let service: LedgerService;
    let party: Party;
    let business: Business | null;

    const buildService = async () => {
      const manager = {
        createQueryBuilder: () => ({
          setLock: () => ({
            where: () => ({ getOne: async () => party }),
          }),
        }),
        findOne: async (Entity: any) => (Entity === Business ? business : null),
        findOneBy: async () => null,
        create: (Entity: any, obj: any) => Object.assign(new Entity(), obj),
        save: async (e: any) => {
          if (e instanceof LedgerEntry) e.id = e.id ?? 'entry-1';
          return e;
        },
      };

      const moduleRef = await Test.createTestingModule({
        providers: [
          LedgerService,
          { provide: getRepositoryToken(LedgerEntry), useValue: {} },
          { provide: getRepositoryToken(Party), useValue: {} },
          { provide: SmsService, useValue: { sendSms } },
          { provide: DataSource, useValue: { transaction: (cb: any) => cb(manager) } },
        ],
      }).compile();
      return moduleRef.get(LedgerService);
    };

    beforeEach(async () => {
      sendSms = jest.fn().mockResolvedValue(true);
      business = Object.assign(new Business(), {
        id: BUSINESS_ID,
        name: BUSINESS_NAME,
      });
      party = Object.assign(new Party(), {
        id: PARTY_ID,
        businessId: BUSINESS_ID,
        type: 'CUSTOMER',
        phone: '+919876543210',
        currentBalance: 0,
      });
      service = await buildService();
    });

    const record = (type: 'GAVE' | 'GOT', amount: number) =>
      service.create(BUSINESS_ID, { partyId: PARTY_ID, amount, type });

    it('names the business instead of printing its id', async () => {
      await record('GAVE', 500);

      expect(lastMessage()).toContain(BUSINESS_NAME);
      expect(lastMessage()).not.toContain(BUSINESS_ID);
    });

    it('never leaks a uuid into the message', async () => {
      await record('GAVE', 500);

      expect(lastMessage()).not.toMatch(
        /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i,
      );
    });

    it('tells a customer who owes money that a balance is due', async () => {
      await record('GAVE', 500);

      expect(lastMessage()).toContain('Balance due: Rs 500.00');
    });

    it('does NOT tell a customer in credit that they owe money', async () => {
      // They paid 500 against a zero balance, so the shop owes them.
      await record('GOT', 500);

      expect(lastMessage()).toContain('You are in credit: Rs 500.00');
      expect(lastMessage()).not.toContain('Balance due');
    });

    it('reports a cleared account as settled', async () => {
      party.currentBalance = 500;

      await record('GOT', 500);

      expect(lastMessage()).toContain('settled');
      expect(lastMessage()).not.toContain('Balance due');
    });

    it('inverts the reading for a supplier', async () => {
      party.type = 'SUPPLIER';

      // GOT from a supplier means goods received on credit: we owe them.
      await record('GOT', 700);

      expect(lastMessage()).toContain('You are in credit: Rs 700.00');
    });

    it('formats amounts to two decimals', async () => {
      await record('GAVE', 1250.5);

      expect(lastMessage()).toContain('Rs 1250.50');
    });

    it('falls back to a generic name when the business row is missing', async () => {
      business = null;

      await record('GAVE', 500);

      expect(lastMessage()).toContain('Your shop');
      expect(lastMessage()).not.toContain('undefined');
    });
  });

  describe('welcome message', () => {
    let service: PartyService;
    let business: Business | null;

    beforeEach(async () => {
      sendSms = jest.fn().mockResolvedValue(true);
      business = Object.assign(new Business(), {
        id: BUSINESS_ID,
        name: BUSINESS_NAME,
      });

      const moduleRef = await Test.createTestingModule({
        providers: [
          PartyService,
          {
            provide: getRepositoryToken(Party),
            useValue: {
              create: (o: any) => Object.assign(new Party(), o),
              save: async (p: any) => ({ ...p, id: PARTY_ID }),
              findOneBy: async () => null,
            },
          },
          {
            provide: getRepositoryToken(Business),
            useValue: { findOne: async () => business },
          },
          { provide: SmsService, useValue: { sendSms } },
        ],
      }).compile();

      service = moduleRef.get(PartyService);
    });

    const addParty = () =>
      service.create(BUSINESS_ID, {
        name: 'Rahul Traders',
        phone: '+919876543210',
        type: 'CUSTOMER',
      });

    it('names the business instead of printing its id', async () => {
      await addParty();

      expect(lastMessage()).toContain(BUSINESS_NAME);
      expect(lastMessage()).not.toContain(BUSINESS_ID);
    });

    it('never leaks a uuid into the message', async () => {
      await addParty();

      expect(lastMessage()).not.toMatch(
        /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i,
      );
    });

    it('falls back to a generic name when the business row is missing', async () => {
      business = null;

      await addParty();

      expect(lastMessage()).toContain('your shop');
      expect(lastMessage()).not.toContain('undefined');
    });
  });
});
