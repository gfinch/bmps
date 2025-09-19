// Timezone conversion utilities for Eastern Time

/**
 * Converts Unix timestamp to Eastern Time Date object
 */
export function toEasternTime(timestamp: number): Date {
  return new Date(timestamp);
}

/**
 * Converts Unix timestamp to Eastern Time string (readable format)
 */
export function toEasternTimeString(timestamp: number): string {
  return new Date(timestamp).toLocaleString('en-US', {
    timeZone: 'America/New_York',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });
}

/**
 * Converts Unix timestamp to Eastern Time for TradingView chart format (YYYY-MM-DD HH:mm:ss)
 */
export function toChartTime(timestamp: number): string {
  const date = new Date(timestamp);
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/New_York',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });
  
  const parts = formatter.formatToParts(date);
  const year = parts.find(p => p.type === 'year')?.value;
  const month = parts.find(p => p.type === 'month')?.value;
  const day = parts.find(p => p.type === 'day')?.value;
  const hour = parts.find(p => p.type === 'hour')?.value;
  const minute = parts.find(p => p.type === 'minute')?.value;
  const second = parts.find(p => p.type === 'second')?.value;
  
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
}

/**
 * Converts Unix timestamp to TradingView time format (seconds since epoch)
 * TradingView expects time in seconds, not milliseconds
 */
export function toTradingViewTime(timestamp: number): number {
  return Math.floor(timestamp / 1000);
}

/**
 * Gets the current Eastern Time as Unix timestamp
 */
export function getCurrentEasternTime(): number {
  return Date.now();
}

/**
 * Gets Eastern Time of day from timestamp (hours, minutes, seconds)
 */
export function getEasternTimeOfDay(timestamp: number): { hours: number; minutes: number; seconds: number } {
  const date = new Date(timestamp);
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/New_York',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });
  
  const parts = formatter.formatToParts(date);
  const hours = parseInt(parts.find(p => p.type === 'hour')?.value || '0');
  const minutes = parseInt(parts.find(p => p.type === 'minute')?.value || '0');
  const seconds = parseInt(parts.find(p => p.type === 'second')?.value || '0');
  
  return { hours, minutes, seconds };
}

/**
 * Creates a timestamp for a specific time of day in Eastern timezone on a given date
 */
export function createEasternTimestamp(date: Date, hours: number, minutes: number = 0, seconds: number = 0): number {
  // Create a date in Eastern timezone
  const easternDate = new Date(date.toLocaleString('en-US', { timeZone: 'America/New_York' }));
  easternDate.setHours(hours, minutes, seconds, 0);
  
  // Convert back to UTC timestamp
  const utcDate = new Date(easternDate.getTime() - (easternDate.getTimezoneOffset() * 60000));
  return utcDate.getTime();
}

/**
 * Formats time for display in playback controls (HH:MM:SS ET)
 */
export function formatPlaybackTime(timestamp: number): string {
  const timeOfDay = getEasternTimeOfDay(timestamp);
  const hours = timeOfDay.hours.toString().padStart(2, '0');
  const minutes = timeOfDay.minutes.toString().padStart(2, '0');
  const seconds = timeOfDay.seconds.toString().padStart(2, '0');
  return `${hours}:${minutes}:${seconds} ET`;
}

/**
 * Gets the start of day in Eastern timezone for a given timestamp
 */
export function getEasternStartOfDay(timestamp: number): number {
  const date = new Date(timestamp);
  const easternDate = new Date(date.toLocaleString('en-US', { timeZone: 'America/New_York' }));
  easternDate.setHours(0, 0, 0, 0);
  
  // Convert back to UTC timestamp
  const utcDate = new Date(easternDate.getTime() - (easternDate.getTimezoneOffset() * 60000));
  return utcDate.getTime();
}

/**
 * Gets the end of day in Eastern timezone for a given timestamp
 */
export function getEasternEndOfDay(timestamp: number): number {
  const date = new Date(timestamp);
  const easternDate = new Date(date.toLocaleString('en-US', { timeZone: 'America/New_York' }));
  easternDate.setHours(23, 59, 59, 999);
  
  // Convert back to UTC timestamp
  const utcDate = new Date(easternDate.getTime() - (easternDate.getTimezoneOffset() * 60000));
  return utcDate.getTime();
}