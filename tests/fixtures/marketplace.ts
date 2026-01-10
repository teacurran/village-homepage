/**
 * Test fixtures for marketplace E2E tests.
 *
 * Provides sample data for:
 * - Listings
 * - Categories
 * - Search queries
 * - Contact messages
 */

export interface ListingFixture {
  title: string;
  description: string;
  category: string;
  subcategory: string;
  price: number;
  location: string;
}

export const sampleListings: ListingFixture[] = [
  {
    title: 'Vintage Road Bicycle',
    description: 'Well-maintained vintage road bike. Great condition, barely used. Original parts including Campagnolo groupset.',
    category: 'for-sale',
    subcategory: 'bicycles',
    price: 250,
    location: 'San Francisco, CA',
  },
  {
    title: 'Apartment for Rent - 1BR/1BA',
    description: 'Cozy one-bedroom apartment in the Mission District. Hardwood floors, updated kitchen, laundry in unit.',
    category: 'housing',
    subcategory: 'apartments',
    price: 2500,
    location: 'San Francisco, CA',
  },
  {
    title: 'Software Engineer - Full-time',
    description: 'Seeking experienced software engineer for fast-growing startup. Remote OK. Competitive salary and equity.',
    category: 'jobs',
    subcategory: 'software',
    price: 0,
    location: 'San Francisco, CA',
  },
  {
    title: 'Guitar Lessons',
    description: 'Professional guitar instructor with 15 years experience. All styles and skill levels welcome. $50/hour.',
    category: 'services',
    subcategory: 'lessons',
    price: 50,
    location: 'Oakland, CA',
  },
  {
    title: 'Community Garden Plot Available',
    description: 'Shared community garden plot available for the growing season. Great for beginners. $20/month.',
    category: 'community',
    subcategory: 'general',
    price: 20,
    location: 'Berkeley, CA',
  },
];

export const searchQueries = {
  bicycleQuery: 'bicycle',
  housingQuery: 'apartment rent',
  jobQuery: 'software engineer',
  serviceQuery: 'guitar lessons',
  communityQuery: 'community garden',
};

export const contactMessages = {
  inquiry: {
    senderName: 'John Doe',
    senderEmail: 'john.doe@example.com',
    message: 'Is this item still available? Can you provide more details about the condition?',
  },
  spam: {
    senderName: 'Spammer',
    senderEmail: 'spam@example.com',
    message: 'CLICK HERE TO WIN FREE MONEY!!! http://scam.com',
  },
};

export const flagReasons = [
  { value: 'spam', label: 'Spam or scam' },
  { value: 'prohibited', label: 'Prohibited item' },
  { value: 'duplicate', label: 'Duplicate posting' },
  { value: 'offensive', label: 'Offensive content' },
  { value: 'other', label: 'Other (specify)' },
];
