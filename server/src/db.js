import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

const SCHEMA = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8');

/**
 * 打开账本数据库并确保 schema 就绪。
 *
 * @param {string} location 数据库路径，或 `:memory:`。
 * @returns {DatabaseSync}
 */
export function openDatabase(location = ':memory:') {
  const db = new DatabaseSync(location);
  if (location !== ':memory:') db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec(SCHEMA);
  return db;
}

/**
 * @param {DatabaseSync} db 数据库。
 * @param {string} sql 语句。
 * @param {Array} params 位置参数。
 * @returns {object} `{ changes, lastInsertRowid }`。
 */
export function run(db, sql, params = []) {
  return db.prepare(sql).run(...params);
}

/**
 * @returns {object|null} 首行，无结果时为 null。
 */
export function get(db, sql, params = []) {
  return db.prepare(sql).get(...params) ?? null;
}

/**
 * @returns {Array<object>} 全部结果行。
 */
export function all(db, sql, params = []) {
  return db.prepare(sql).all(...params);
}

/** @returns {number} 插入行的整数主键。 */
export function lastId(result) {
  return Number(result.lastInsertRowid);
}

/**
 * 在事务中执行。SQLite 没有嵌套事务，所以回调内部不要再次调用。
 *
 * @param {DatabaseSync} db 数据库。
 * @param {Function} fn 事务体。
 * @returns {*} 事务体的返回值。
 */
export function inTransaction(db, fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}
