/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.util;

import com.pgvector.PGvector;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Hibernate UserType for PostgreSQL pgvector extension support.
 *
 * <p>
 * Maps Java {@code float[]} to PostgreSQL {@code vector(N)} column type. Used for storing Anthropic embedding vectors
 * (1536 dimensions) for semantic search functionality per Feature I4.T5.
 *
 * <p>
 * This type enables transparent conversion between:
 * <ul>
 * <li>Java: {@code float[]} array (e.g., {@code new float[]{0.1f, 0.2f, ...}})</li>
 * <li>JDBC: {@code PGvector} object (from pgvector JDBC driver)</li>
 * <li>PostgreSQL: {@code vector(1536)} column (stored via pgvector extension)</li>
 * </ul>
 *
 * <p>
 * <b>Usage in entities:</b>
 *
 * <pre>
 * &#64;Column(
 *         name = "content_embedding",
 *         columnDefinition = "vector(1536)")
 * &#64;Type(PgVectorType.class)
 * public float[] contentEmbedding;
 * </pre>
 *
 * <p>
 * <b>Implementation Notes:</b>
 * <ul>
 * <li>Uses pgvector JDBC driver's {@code PGvector} class for database conversion</li>
 * <li>Handles null values gracefully (null Java array → NULL database value)</li>
 * <li>Immutable by design - returns defensive copies in {@code deepCopy()}</li>
 * <li>Thread-safe - no shared state</li>
 * </ul>
 *
 * @see com.pgvector.PGvector
 * @see villagecompute.homepage.services.SemanticSearchService
 */
public class PgVectorType implements UserType<float[]> {

    /**
     * Returns the SQL type code for pgvector column.
     *
     * <p>
     * Uses {@code Types.OTHER} to indicate a database-specific type not covered by standard JDBC types.
     *
     * @return SQL type code
     */
    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    /**
     * Returns the Java class this UserType handles.
     *
     * @return {@code float[].class}
     */
    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    /**
     * Compares two embedding vectors for equality.
     *
     * <p>
     * Uses element-wise comparison for float arrays. Returns true only if both arrays have same length and identical
     * element values.
     *
     * @param x
     *            first vector
     * @param y
     *            second vector
     * @return true if vectors are equal, false otherwise
     */
    @Override
    public boolean equals(float[] x, float[] y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null) {
            return false;
        }
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            if (Float.compare(x[i], y[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes hash code for embedding vector.
     *
     * <p>
     * Uses {@code java.util.Arrays.hashCode()} for consistent hashing behavior.
     *
     * @param x
     *            the vector to hash
     * @return hash code
     */
    @Override
    public int hashCode(float[] x) {
        return x != null ? java.util.Arrays.hashCode(x) : 0;
    }

    /**
     * Reads embedding vector from JDBC ResultSet.
     *
     * <p>
     * Converts PostgreSQL {@code vector} column to Java {@code float[]} array via pgvector JDBC driver.
     *
     * @param rs
     *            JDBC result set
     * @param position
     *            column position (1-indexed)
     * @param session
     *            Hibernate session
     * @param owner
     *            entity instance
     * @return float array, or null if database value is NULL
     * @throws SQLException
     *             if column read fails
     */
    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        Object obj = rs.getObject(position);
        if (obj == null) {
            return null;
        }

        if (obj instanceof PGvector pgVector) {
            return pgVector.toArray();
        }

        // Fallback: try to parse as string representation
        String vectorStr = obj.toString();
        return parseVectorString(vectorStr);
    }

    /**
     * Writes embedding vector to JDBC PreparedStatement.
     *
     * <p>
     * Converts Java {@code float[]} array to PostgreSQL {@code vector} column via pgvector JDBC driver.
     *
     * @param st
     *            JDBC prepared statement
     * @param value
     *            float array to write
     * @param index
     *            parameter index (1-indexed)
     * @param session
     *            Hibernate session
     * @throws SQLException
     *             if parameter set fails
     */
    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            PGvector pgVector = new PGvector(value);
            st.setObject(index, pgVector);
        }
    }

    /**
     * Creates a deep copy of embedding vector.
     *
     * <p>
     * Returns defensive copy to prevent external mutation of entity state.
     *
     * @param value
     *            vector to copy
     * @return new float array with same values, or null if input is null
     */
    @Override
    public float[] deepCopy(float[] value) {
        if (value == null) {
            return null;
        }
        return java.util.Arrays.copyOf(value, value.length);
    }

    /**
     * Indicates whether this type is mutable.
     *
     * <p>
     * Returns true because float arrays are mutable in Java. Hibernate will track changes and call {@code deepCopy()}
     * as needed.
     *
     * @return true
     */
    @Override
    public boolean isMutable() {
        return true;
    }

    /**
     * Serializes embedding vector for caching.
     *
     * <p>
     * Returns defensive copy of float array for cache storage.
     *
     * @param value
     *            vector to serialize
     * @return serialized vector
     */
    @Override
    public Serializable disassemble(float[] value) {
        return deepCopy(value);
    }

    /**
     * Deserializes embedding vector from cache.
     *
     * <p>
     * Returns defensive copy of cached float array.
     *
     * @param cached
     *            cached vector
     * @return deserialized vector
     */
    @Override
    public float[] assemble(Serializable cached, Object owner) {
        if (cached == null) {
            return null;
        }
        return deepCopy((float[]) cached);
    }

    /**
     * Parses pgvector string representation to float array.
     *
     * <p>
     * Pgvector string format: {@code "[0.1, 0.2, 0.3]"} (JSON array style)
     *
     * @param vectorStr
     *            pgvector string representation
     * @return parsed float array
     * @throws HibernateException
     *             if parsing fails
     */
    private float[] parseVectorString(String vectorStr) {
        try {
            // Remove brackets and split by comma
            String trimmed = vectorStr.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }

            String[] parts = trimmed.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        } catch (Exception e) {
            throw new HibernateException("Failed to parse pgvector string: " + vectorStr, e);
        }
    }
}
