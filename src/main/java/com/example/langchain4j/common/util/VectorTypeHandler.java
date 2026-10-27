package com.example.langchain4j.common.util;

import com.pgvector.PGvector;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * pgvector 类型处理器：float[] 与 PG vector 类型互转。
 * 使用方式：XML 中显式声明 typeHandler，或通过 mybatis.type-handlers-package 全局扫描。
 */
public class VectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, new PGvector(parameter), Types.OTHER);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toArray(rs.getObject(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toArray(rs.getObject(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toArray(cs.getObject(columnIndex));
    }

    private float[] toArray(Object value) {
        if (value instanceof PGvector pgvector) {
            return pgvector.toArray();
        }
        // 兜底：异常数据返回 null，避免反序列化中断主流程
        return null;
    }
}
