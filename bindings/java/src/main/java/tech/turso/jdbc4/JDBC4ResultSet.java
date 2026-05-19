package tech.turso.jdbc4;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import tech.turso.annotations.Nullable;
import tech.turso.annotations.SkipNullableCheck;
import tech.turso.core.TursoResultSet;

/** JDBC 4 ResultSet implementation for Turso databases. */
public final class JDBC4ResultSet implements ResultSet, ResultSetMetaData {

  private final TursoResultSet resultSet;
  @Nullable private final Statement statement;
  private boolean wasNull = false;

  /**
   * Creates a new JDBC4ResultSet.
   *
   * @param resultSet the underlying Turso result set
   * @param statement the statement that created this result set
   */
  public JDBC4ResultSet(TursoResultSet resultSet, @Nullable Statement statement) {
    this.resultSet = resultSet;
    this.statement = statement;
  }

  @Override
  public boolean next() throws SQLException {
    return resultSet.next();
  }

  @Override
  public void close() throws SQLException {
    resultSet.close();
  }

  @Override
  public boolean wasNull() throws SQLException {
    return wasNull;
  }

  @Override
  @Nullable
  public String getString(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    if (result instanceof String) {
      return (String) result;
    }
    if (result instanceof byte[]) {
      // SQLite's CAST(blob AS TEXT) reinterprets the raw bytes as text in the
      // database encoding (UTF-8 by default). Match that behavior; invalid
      // UTF-8 sequences are replaced with U+FFFD (the JDK default), which is
      // the same observable outcome xerial sqlite-jdbc produces via the JNI
      // sqlite3_column_text path.
      return new String((byte[]) result, StandardCharsets.UTF_8);
    }
    if (result instanceof Double) {
      return realToText((Double) result);
    }
    if (result instanceof Float) {
      return realToText(((Float) result).doubleValue());
    }
    // INTEGER (Long/Integer) and any remaining numeric types coerce to TEXT via
    // their natural string form, matching SQLite's CAST(... AS TEXT).
    return result.toString();
  }

  /**
   * Formats a double the way SQLite (and Turso's engine) formats REAL values
   * when coercing them to TEXT, i.e. {@code CAST(value AS TEXT)} and
   * {@code printf("%g", value)} with 15 significant digits.
   *
   * <p>This mirrors {@code core::numeric::format_float}: it uses a fixed-point
   * form when the decimal exponent is in {@code -4..=14} and a scientific
   * form otherwise. The scientific form is {@code "<digits>.<digits>e<sign><exp>"},
   * the exponent is at least two digits wide and always carries an explicit
   * sign — so e.g. {@code 1e20} becomes {@code "1.0e+20"} and {@code 1e-7}
   * becomes {@code "1.0e-07"}, both matching SQLite. Trailing zeros in the
   * mantissa are stripped, but at least one digit always follows the decimal
   * point (so {@code 123456789012345.0} stays as {@code "123456789012345.0"}
   * rather than collapsing to {@code "123456789012345"}).
   */
  static String realToText(double v) {
    if (Double.isNaN(v)) {
      // Matches core::numeric::decompose_float: NaN renders as "".
      return "";
    }
    if (Double.isInfinite(v)) {
      return v > 0 ? "Inf" : "-Inf";
    }
    if (v == 0.0) {
      // Covers both +0.0 and -0.0; SQLite renders both as "0.0".
      return "0.0";
    }

    // Use Java's %.15g which produces 15 significant digits — same precision
    // SQLite uses for CAST(real AS TEXT). The output is either fixed or
    // scientific depending on magnitude.
    String s = String.format(Locale.ROOT, "%.15g", v);

    int ePos = s.indexOf('e');
    if (ePos < 0) {
      ePos = s.indexOf('E');
    }

    if (ePos >= 0) {
      // Scientific form: trim trailing zeros from the mantissa, then normalize
      // the exponent ("e+05" stays "e+05"; SQLite always emits an explicit
      // sign and at least 2-digit exponent, which is also Java's default).
      String mantissa = s.substring(0, ePos);
      String exponent = s.substring(ePos + 1); // includes sign like "+20" or "-07"
      mantissa = stripTrailingZeros(mantissa);
      char sign;
      String digits;
      if (exponent.charAt(0) == '+' || exponent.charAt(0) == '-') {
        sign = exponent.charAt(0);
        digits = exponent.substring(1);
      } else {
        sign = '+';
        digits = exponent;
      }
      // Strip leading zeros but keep at least two digits, matching SQLite.
      int firstNonZero = 0;
      while (firstNonZero < digits.length() - 1 && digits.charAt(firstNonZero) == '0') {
        firstNonZero++;
      }
      digits = digits.substring(firstNonZero);
      if (digits.length() < 2) {
        digits = "0" + digits;
      }
      return mantissa + "e" + sign + digits;
    }

    // Fixed form: ensure the result has a decimal point with at least one digit
    // after it (e.g. "100000000000000" -> "100000000000000.0"), then strip
    // trailing zeros after the point while keeping at least one fractional digit.
    if (s.indexOf('.') < 0) {
      return s + ".0";
    }
    return stripTrailingZeros(s);
  }

  /**
   * Strips trailing zeros from a fixed-point decimal string, leaving at least
   * one digit after the decimal point. If the string has no decimal point it
   * is returned unchanged.
   */
  private static String stripTrailingZeros(String s) {
    int dot = s.indexOf('.');
    if (dot < 0) {
      return s;
    }
    int end = s.length();
    while (end > dot + 2 && s.charAt(end - 1) == '0') {
      end--;
    }
    return s.substring(0, end);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return false;
    }
    return wrapTypeConversion(() -> (Long) result != 0);
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> ((Long) result).byteValue());
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> ((Long) result).shortValue());
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> ((Long) result).intValue());
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> (long) result);
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> ((Double) result).floatValue());
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> (double) result);
  }

  // TODO: customize rounding mode?
  @Override
  @Nullable
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    final double doubleResult = wrapTypeConversion(() -> (double) result);
    final BigDecimal bigDecimalResult = BigDecimal.valueOf(doubleResult);
    return bigDecimalResult.setScale(scale, RoundingMode.HALF_UP);
  }

  @Override
  @Nullable
  public byte[] getBytes(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(() -> (byte[]) result);
  }

  @Override
  @Nullable
  public Date getDate(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            byte[] bytes = (byte[]) result;
            if (bytes.length == Long.BYTES) {
              long time = ByteBuffer.wrap(bytes).getLong();
              return new Date(time);
            }
          }
          throw new SQLException("Cannot convert value to Date: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public Time getTime(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            byte[] bytes = (byte[]) result;
            if (bytes.length == Long.BYTES) {
              long time = ByteBuffer.wrap(bytes).getLong();
              return new Time(time);
            }
          }
          throw new SQLException("Cannot convert value to Date: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            byte[] bytes = (byte[]) result;
            if (bytes.length == Long.BYTES) {
              long time = ByteBuffer.wrap(bytes).getLong();
              return new Timestamp(time);
            }
          }
          throw new SQLException("Cannot convert value to Timestamp: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof String) {
            return new ByteArrayInputStream(((String) result).getBytes("US-ASCII"));
          } else if (result instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) result);
          }
          throw new SQLException("Cannot convert to ASCII stream: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof String) {
            return new ByteArrayInputStream(((String) result).getBytes("UTF-8"));
          } else if (result instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) result);
          }
          throw new SQLException("Cannot convert to Unicode stream: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) result);
          }
          throw new SQLException("Cannot convert to binary stream: " + result.getClass());
        });
  }

  @Override
  @Nullable
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  @Nullable
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  @Override
  @Nullable
  public Date getDate(String columnLabel) throws SQLException {
    final Object result = resultSet.get(columnLabel);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            byte[] bytes = (byte[]) result;
            if (bytes.length == Long.BYTES) {
              long time = ByteBuffer.wrap(bytes).getLong();
              return new Date(time);
            }
          }
          // Try to parse as string if it's stored as TEXT
          if (result instanceof String) {
            return Date.valueOf((String) result);
          }
          throw new SQLException("Cannot convert value to Date: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    return getAsciiStream(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    return getUnicodeStream(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    return getBinaryStream(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public SQLWarning getWarnings() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void clearWarnings() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getCursorName() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return this;
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result;
  }

  @Override
  @SkipNullableCheck
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    if (columnLabel == null || columnLabel.isEmpty()) {
      throw new SQLException("column name not found");
    }

    final String[] columnNames = resultSet.getColumnNames();
    for (int i = 0; i < columnNames.length; i++) {
      if (columnNames[i].equals(columnLabel)) {
        return i + 1;
      }
    }
    throw new SQLException("column name " + columnLabel + " not found");
  }

  @Override
  @SkipNullableCheck
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(() -> new StringReader((String) result));
  }

  @Override
  @Nullable
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return getCharacterStream(findColumn(columnLabel));
  }

  @Override
  @Nullable
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    final double doubleResult = wrapTypeConversion(() -> (double) result);
    return BigDecimal.valueOf(doubleResult);
  }

  @Override
  @SkipNullableCheck
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    // Empty ResultSet should return false per JDBC spec
    if (resultSet.isEmpty()) {
      return false;
    }
    return resultSet.isOpen() && resultSet.getRow() == 0 && !resultSet.isPastLastRow();
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    return resultSet.isOpen() && resultSet.isPastLastRow();
  }

  @Override
  public boolean isFirst() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isLast() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void beforeFirst() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void afterLast() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean first() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean last() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getRow() throws SQLException {
    return resultSet.getRow();
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean previous() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getFetchDirection() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getFetchSize() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getType() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getConcurrency() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean rowInserted() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void insertRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void deleteRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void refreshRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @Nullable
  public Statement getStatement() throws SQLException {
    return statement;
  }

  @Override
  @SkipNullableCheck
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Ref getRef(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Blob getBlob(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Clob getClob(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Array getArray(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Ref getRef(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Blob getBlob(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Clob getClob(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Array getArray(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @Nullable
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    final Date date = getDate(columnIndex);
    if (date == null || cal == null) {
      return date;
    }
    return new Date(date.getTime() + calculateTimezoneOffset(date.getTime(), cal));
  }

  @Override
  @Nullable
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(findColumn(columnLabel), cal);
  }

  @Override
  @Nullable
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    final Time time = getTime(columnIndex);
    if (time == null || cal == null) {
      return time;
    }
    return new Time(time.getTime() + calculateTimezoneOffset(time.getTime(), cal));
  }

  @Override
  @SkipNullableCheck
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(findColumn(columnLabel), cal);
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    final Timestamp timestamp = getTimestamp(columnIndex);
    if (timestamp == null || cal == null) {
      return timestamp;
    }
    return new Timestamp(timestamp.getTime() + calculateTimezoneOffset(timestamp.getTime(), cal));
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(findColumn(columnLabel), cal);
  }

  @Override
  @SkipNullableCheck
  public URL getURL(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public URL getURL(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public RowId getRowId(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public RowId getRowId(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getHoldability() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isClosed() throws SQLException {
    return !resultSet.isOpen();
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public NClob getNClob(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public NClob getNClob(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getColumnCount() throws SQLException {
    return this.resultSet.getColumnNames().length;
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int isNullable(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    return Integer.MAX_VALUE;
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    // TODO: should consider "AS" keyword
    return getColumnName(column);
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    if (column > 0 && column <= resultSet.getColumnNames().length) {
      return resultSet.getColumnNames()[column - 1];
    }

    throw new SQLException("Index out of bound: " + column);
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getScale(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getTableName(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  private long calculateTimezoneOffset(long timeMillis, Calendar targetCal) {
    Calendar localCal = Calendar.getInstance();
    return targetCal.getTimeZone().getOffset(timeMillis)
        - localCal.getTimeZone().getOffset(timeMillis);
  }

  /**
   * Functional interface for result set value suppliers.
   *
   * @param <T> the type of value to supply
   */
  @FunctionalInterface
  public interface ResultSetSupplier<T> {
    /**
     * Gets a result from the result set.
     *
     * @return the result value
     * @throws Exception if an error occurs
     */
    T get() throws Exception;
  }

  private <T> T wrapTypeConversion(ResultSetSupplier<T> supplier) throws SQLException {
    try {
      return supplier.get();
    } catch (Exception e) {
      throw new SQLException("Type conversion failed: " + e);
    }
  }
}
