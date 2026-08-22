import { IsIn } from 'class-validator';

/**
 * A party's Daily/Weekly/Monthly bucket.
 *
 * Required rather than optional here: unlike create, this endpoint exists
 * solely to set the value, so an omitted field would be a silent no-op
 * rather than a meaningful default.
 */
export class UpdateCollectionFrequencyDto {
  @IsIn(['DAILY', 'WEEKLY', 'MONTHLY'])
  collectionFrequency: 'DAILY' | 'WEEKLY' | 'MONTHLY';
}
