import { dirname, resolve } from "node:path";
import {
  type AccidentEvent,
  exactKeys,
  MAX_ACCIDENT_RECORDS,
  object,
  sameAccident,
  validAccidentEvent,
} from "./accident_types.ts";

export type StoredAccident = {
  created: AccidentEvent;
  event: AccidentEvent;
};
type Mutation<T> = {
  result: T;
  records?: readonly StoredAccident[];
};
type Persist = (records: readonly StoredAccident[]) => Promise<void>;

/** Serializes mutations and publishes state only after the durable write succeeds. */
export class AccidentRepository {
  private records: StoredAccident[];
  private pending: Promise<void> = Promise.resolve();

  constructor(records: readonly StoredAccident[], private persist: Persist) {
    this.records = structuredClone([...records]);
  }

  snapshot(): StoredAccident[] {
    return structuredClone(this.records);
  }

  transact<T>(mutate: (records: StoredAccident[]) => Mutation<T>): Promise<T> {
    const operation = this.pending.then(async () => {
      const change = mutate(this.snapshot());
      if (change.records) {
        const next = structuredClone([...change.records]);
        await this.persist(next);
        this.records = next;
      }
      return change.result;
    });
    this.pending = operation.then(() => {}, () => {});
    return operation;
  }
}

function storedRecords(value: unknown): StoredAccident[] {
  if (
    !object(value) || !exactKeys(value, ["schemaVersion", "records"]) ||
    value.schemaVersion !== 1 || !Array.isArray(value.records) ||
    value.records.length > MAX_ACCIDENT_RECORDS
  ) throw new Error("Invalid accident repository schema");
  const identifiers = new Set<string>();
  for (const record of value.records) {
    if (
      !object(record) || !exactKeys(record, ["created", "event"]) ||
      !validAccidentEvent(record.created) ||
      record.created.status !== "ACTIVE" ||
      !validAccidentEvent(record.event) ||
      !sameAccident(
        { ...record.event, version: 1, status: "ACTIVE" },
        record.created,
      ) || identifiers.has(record.created.id)
    ) throw new Error("Invalid accident repository record");
    identifiers.add(record.created.id);
  }
  return value.records as StoredAccident[];
}

async function removeTemporary(path: string) {
  try {
    await Deno.remove(path);
  } catch (error) {
    if (!(error instanceof Deno.errors.NotFound)) throw error;
  }
}

async function atomicWrite(path: string, records: readonly StoredAccident[]) {
  const directory = dirname(path);
  await Deno.mkdir(directory, { recursive: true });
  const temporary = `${path}.${crypto.randomUUID()}.tmp`;
  let file: Deno.FsFile | undefined;
  let directoryFile: Deno.FsFile | undefined;
  try {
    // Both file contents and the directory entry are flushed before an ACK.
    directoryFile = await Deno.open(directory, { read: true });
    file = await Deno.open(temporary, {
      write: true,
      createNew: true,
      mode: 0o600,
    });
    const bytes = new TextEncoder().encode(
      JSON.stringify({ schemaVersion: 1, records }) + "\n",
    );
    let offset = 0;
    while (offset < bytes.length) {
      const written = await file.write(bytes.subarray(offset));
      if (written <= 0) throw new Error("Accident repository write stalled");
      offset += written;
    }
    await file.sync();
    file.close();
    file = undefined;
    await Deno.rename(temporary, path);
    await directoryFile.sync();
  } finally {
    file?.close();
    directoryFile?.close();
    await removeTemporary(temporary);
  }
}

/** Missing file starts empty; corrupt or unreadable storage fails startup. */
export async function openAccidentRepository(
  dataPath: string,
): Promise<AccidentRepository> {
  const path = resolve(dataPath);
  let records: StoredAccident[];
  let raw: string;
  try {
    raw = await Deno.readTextFile(path);
  } catch (error) {
    if (!(error instanceof Deno.errors.NotFound)) throw error;
    return new AccidentRepository([], (next) => atomicWrite(path, next));
  }
  try {
    records = storedRecords(JSON.parse(raw));
  } catch (error) {
    throw new Error(`Cannot load accident repository: ${path}`, {
      cause: error,
    });
  }
  return new AccidentRepository(records, (next) => atomicWrite(path, next));
}
