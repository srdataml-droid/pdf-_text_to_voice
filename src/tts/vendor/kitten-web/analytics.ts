import { modelRepoId, type KittenTTSModelId } from './KittenModel.js';
import { type KittenTTSVoiceId } from './KittenVoice.js';

export const ANALYTICS_ENDPOINT = 'https://kittenmlanalytics.com/v1/track';
export const SDK_TYPE = 'web';
export const SDK_VERSION = '0.1.0';
export const DEFAULT_ANALYTICS_TIMEOUT_SECONDS = 3;

export type AnalyticsGeneration = 'speak' | 'stream' | 'wav';
export type AnalyticsAssetSource = 'bundled' | 'runtime-download' | 'cache';

export interface AnalyticsModelInfo {
  selectedModel: string;
  modelVersion: string;
}

export interface AnalyticsEventInput {
  selectedVoice: KittenTTSVoiceId;
  generation: AnalyticsGeneration;
  sdkErrorCode?: string;
}

export interface AnalyticsClientOptions extends AnalyticsModelInfo {
  assetSource: AnalyticsAssetSource;
  enabled?: boolean;
  endpoint?: string;
  sdkVersion?: string;
  timeoutSeconds?: number;
  anonymousIdPath?: string;
  postJson?: AnalyticsPostJson;
  asyncDelivery?: boolean;
}

export type AnalyticsPayload = Record<string, string>;
export type AnalyticsPostJson = (
  endpoint: string,
  payload: AnalyticsPayload,
  timeoutSeconds: number,
) => Promise<void> | void;

const MODEL_VERSION_RE = /^(?<model>.+?)-(?<version>\d+(?:\.\d+)*(?:-[A-Za-z0-9]+)*)$/;
const ANONYMOUS_ID_STORAGE_KEY = 'kittentts_web_analytics_id';

export class AnalyticsClient {
  private readonly enabled: boolean;
  private readonly endpoint: string;
  private readonly sdkVersion: string;
  private readonly selectedModel: string;
  private readonly modelVersion: string;
  private readonly assetSource: AnalyticsAssetSource;
  private readonly timeoutSeconds: number;
  private readonly postJson: AnalyticsPostJson;
  private readonly asyncDelivery: boolean;
  private readonly anonymousIdPath?: string;
  private anonymousId: string | null = null;

  constructor(options: AnalyticsClientOptions) {
    this.enabled = options.enabled ?? true;
    this.endpoint = options.endpoint ?? ANALYTICS_ENDPOINT;
    this.sdkVersion = options.sdkVersion ?? SDK_VERSION;
    this.selectedModel = options.selectedModel;
    this.modelVersion = options.modelVersion;
    this.assetSource = options.assetSource;
    this.timeoutSeconds = options.timeoutSeconds ?? DEFAULT_ANALYTICS_TIMEOUT_SECONDS;
    this.postJson = options.postJson ?? postJsonRequest;
    this.asyncDelivery = options.asyncDelivery ?? true;
    this.anonymousIdPath = options.anonymousIdPath;
  }

  async trackGeneration(input: AnalyticsEventInput): Promise<void> {
    if (!this.enabled) return;

    try {
      const payload = await this.createPayload(input);
      if (this.asyncDelivery) {
        void this.send(payload);
        return;
      }
      await this.send(payload);
    } catch {
      // Analytics must never affect TTS calls.
    }
  }

  private async createPayload(input: AnalyticsEventInput): Promise<AnalyticsPayload> {
    const payload: AnalyticsPayload = {
      anonymous_id: await this.getAnonymousId(),
      client_event_id: randomUUID(),
      timestamp: new Date().toISOString(),
      sdk_version: this.sdkVersion,
      sdk_type: SDK_TYPE,
      platform: currentPlatform(),
      runtime_version: runtimeVersion(),
      selected_model: this.selectedModel,
      model_version: this.modelVersion,
      selected_voice: input.selectedVoice,
      generation: input.generation,
      asset_source: this.assetSource,
    };

    if (input.sdkErrorCode) {
      payload.sdk_error_code = input.sdkErrorCode;
    }

    return payload;
  }

  private async getAnonymousId(): Promise<string> {
    if (this.anonymousId) return this.anonymousId;
    this.anonymousId = await loadOrCreateAnonymousId(this.anonymousIdPath);
    return this.anonymousId;
  }

  private async send(payload: AnalyticsPayload): Promise<void> {
    try {
      await this.postJson(this.endpoint, payload, this.timeoutSeconds);
    } catch {
      // Swallow analytics delivery errors.
    }
  }
}

export function analyticsModelInfo(model: KittenTTSModelId): AnalyticsModelInfo {
  const repoName = modelRepoId(model);
  const match = MODEL_VERSION_RE.exec(repoName);
  if (!match?.groups) {
    return { selectedModel: repoName, modelVersion: 'unknown' };
  }
  return {
    selectedModel: match.groups.model,
    modelVersion: match.groups.version,
  };
}

export function analyticsErrorCode(error: unknown): string {
  const maybeCode = typeof error === 'object' && error !== null && 'code' in error
    ? (error as { code?: unknown }).code
    : undefined;
  if (typeof maybeCode === 'string' && maybeCode.trim()) {
    return maybeCode.trim();
  }

  const name = error instanceof Error && error.name ? error.name : 'UnknownError';
  return name
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/(?<!^)(?=[A-Z])/g, '_')
    .toUpperCase()
    .replace(/^_+|_+$/g, '') || 'UNKNOWN_ERROR';
}

export async function postJsonRequest(
  endpoint: string,
  payload: AnalyticsPayload,
  timeoutSeconds: number,
): Promise<void> {
  const runFetch = globalThis.fetch?.bind(globalThis);
  if (!runFetch) throw new Error('No fetch implementation is available.');

  const controller = typeof AbortController !== 'undefined'
    ? new AbortController()
    : undefined;
  const timeout = controller
    ? setTimeout(() => controller.abort(), Math.max(1, timeoutSeconds) * 1000)
    : undefined;

  try {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    };
    if (isNodeRuntime()) {
      headers['User-Agent'] = `KittenTTS-Web/${sanitizeHeaderValue(payload.sdk_version || SDK_VERSION)}`;
    }

    const response = await runFetch(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: controller?.signal,
    });

    if (!response.ok) {
      throw new Error(`Analytics request failed with HTTP ${response.status}`);
    }
  } finally {
    if (timeout) clearTimeout(timeout);
  }
}

async function loadOrCreateAnonymousId(customPath?: string): Promise<string> {
  const stored = readBrowserAnonymousId();
  if (stored) return stored;

  const nodePath = customPath ?? await defaultNodeAnonymousIdPath();
  if (nodePath) {
    const fromFile = await readNodeAnonymousId(nodePath);
    if (fromFile) return fromFile;
  }

  const anonymousId = randomUUID();
  writeBrowserAnonymousId(anonymousId);
  if (nodePath) await writeNodeAnonymousId(nodePath, anonymousId);
  return anonymousId;
}

function readBrowserAnonymousId(): string | null {
  if (isNodeRuntime()) return null;
  try {
    const value = globalThis.localStorage?.getItem(ANONYMOUS_ID_STORAGE_KEY)?.trim();
    return value && isUUID(value) ? value : null;
  } catch {
    return null;
  }
}

function writeBrowserAnonymousId(anonymousId: string): void {
  if (isNodeRuntime()) return;
  try {
    globalThis.localStorage?.setItem(ANONYMOUS_ID_STORAGE_KEY, anonymousId);
  } catch {
    // Browser storage can be unavailable in private contexts.
  }
}

async function readNodeAnonymousId(path: string): Promise<string | null> {
  if (!isNodeRuntime()) return null;
  try {
    const fs = await import('node:fs/promises');
    const value = (await fs.readFile(path, 'utf8')).trim();
    return isUUID(value) ? value : null;
  } catch {
    return null;
  }
}

async function writeNodeAnonymousId(path: string, anonymousId: string): Promise<void> {
  if (!isNodeRuntime()) return;
  try {
    const fs = await import('node:fs/promises');
    const nodePath = await import('node:path');
    await fs.mkdir(nodePath.dirname(path), { recursive: true });
    await fs.writeFile(path, anonymousId, 'utf8');
  } catch {
    // Analytics IDs are best-effort only.
  }
}

async function defaultNodeAnonymousIdPath(): Promise<string | null> {
  if (!isNodeRuntime()) return null;
  try {
    const [nodeOs, nodePath] = await Promise.all([
      import('node:os'),
      import('node:path'),
    ]);
    return nodePath.join(nodeOs.homedir(), '.kittentts-web', 'analytics_id');
  } catch {
    return null;
  }
}

function currentPlatform(): string {
  const userAgent = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  if (/iphone|ipad|ipod/i.test(userAgent)) return 'ios';
  if (/android/i.test(userAgent)) return 'android';
  if (/mac os x|macintosh/i.test(userAgent)) return 'macos';
  if (/windows/i.test(userAgent)) return 'windows';
  if (/linux/i.test(userAgent)) return 'linux';

  if (isNodeRuntime()) {
    switch (process.platform) {
      case 'darwin':
        return 'macos';
      case 'win32':
        return 'windows';
      case 'linux':
        return 'linux';
      default:
        return 'unknown';
    }
  }

  return 'unknown';
}

function runtimeVersion(): string {
  if (isNodeRuntime()) {
    const version = process.versions.node.split('.').slice(0, 2).join('.');
    return `node ${version}`;
  }

  const userAgent = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  const browser = parseBrowserVersion(userAgent);
  return browser ? `browser ${browser}` : 'browser unknown';
}

function parseBrowserVersion(userAgent: string): string | null {
  const edge = /\bEdg\/(\d+)/.exec(userAgent);
  if (edge) return `edge ${edge[1]}`;
  const chrome = /\b(?:Chrome|CriOS)\/(\d+)/.exec(userAgent);
  if (chrome) return `chrome ${chrome[1]}`;
  const firefox = /\bFirefox\/(\d+)/.exec(userAgent);
  if (firefox) return `firefox ${firefox[1]}`;
  const safari = /\bVersion\/(\d+).*\bSafari\//.exec(userAgent);
  if (safari) return `safari ${safari[1]}`;
  return null;
}

function isNodeRuntime(): boolean {
  return typeof process !== 'undefined' && Boolean(process.versions?.node);
}

function randomUUID(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();

  return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (char) => {
    const value = Number(char);
    const random = globalThis.crypto?.getRandomValues
      ? globalThis.crypto.getRandomValues(new Uint8Array(1))[0]
      : Math.floor(Math.random() * 256);
    return (value ^ (random & (15 >> (value / 4)))).toString(16);
  });
}

function isUUID(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function sanitizeHeaderValue(value: string): string {
  return value.replace(/[\r\n]/g, ' ').trim() || 'unknown';
}
