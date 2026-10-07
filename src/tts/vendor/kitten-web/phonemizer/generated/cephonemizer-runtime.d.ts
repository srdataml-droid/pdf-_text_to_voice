import type { CEPhonemizerModule } from './cephonemizer.js';

declare const createCEPhonemizerModule: () => Promise<CEPhonemizerModule>;

export default createCEPhonemizerModule;
