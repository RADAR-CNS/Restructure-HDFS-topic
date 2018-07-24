/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.hdfs.data;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.csv.CsvFactory;
import com.fasterxml.jackson.dataformat.csv.CsvGenerator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts deep hierarchical Avro records into flat CSV format. It uses a simple dot syntax in the
 * column names to indicate hierarchy. After the first data record is added, all following
 * records need to have exactly the same hierarchy (or at least a subset of it.)
 */
public class CsvAvroConverter implements RecordConverter {
    private static final CsvSchema EMPTY_SCHEMA = CsvSchema.emptySchema().withHeader();

    public static RecordConverterFactory getFactory() {
        return new RecordConverterFactory() {
            private final CsvFactory CSV_FACTORY = new CsvFactory();
            private final ObjectMapper CSV_MAPPER = new ObjectMapper(CSV_FACTORY);

            @Override
            public RecordConverter converterFor(Writer writer, GenericRecord record,
                    boolean writeHeader, Reader reader) throws IOException {
                return new CsvAvroConverter(CSV_FACTORY, CSV_MAPPER,
                        CSV_MAPPER.readerFor(Map.class).with(EMPTY_SCHEMA), writer, record,
                        writeHeader, reader);
            }

            @Override
            public boolean hasHeader() {
                return true;
            }

            @Override
            public String getExtension() {
                return ".csv";
            }

            @Override
            public Collection<String> getFormats() {
                return Collections.singleton("csv");
            }
        };
    }

    private final ObjectWriter csvWriter;
    private final Map<String, Object> map;
    private final CsvGenerator generator;
    private CsvSchema schema;

    public CsvAvroConverter(CsvFactory factory, ObjectMapper mapper, ObjectReader schemaReader,
            Writer writer, GenericRecord record, boolean writeHeader, Reader reader)
            throws IOException {
        map = new LinkedHashMap<>();

        Map<String, Object> value;

        if (!writeHeader) {
            // If file already exists read the schema from the CSV file
            MappingIterator<Map<String,Object>> iterator = schemaReader.readValues(reader);
            value = iterator.next();
        } else {
            schema = EMPTY_SCHEMA;
            value = convertRecord(record);
        }

        CsvSchema.Builder builder = new CsvSchema.Builder();
        for (String key : value.keySet()) {
            builder.addColumn(key);
        }
        schema = builder.build();

        if (writeHeader) {
            schema = schema.withHeader();
        }

        generator = factory.createGenerator(writer);
        csvWriter = mapper.writer(schema);
    }

    /**
     * Write AVRO record to CSV file.
     * @param record the AVRO record to be written to CSV file
     * @return true if write was successful, false if cannot write record to the current CSV file
     * @throws IOException for other IO and Mapping errors
     */
    @Override
    public boolean writeRecord(GenericRecord record) throws IOException {
        Map<String, Object> localMap = convertRecord(record);
        int len = schema.size();

        if (localMap.size() != len) {
            // Cannot write to same file so return false
            return false;
        } else {
            Iterator<String> localColumnIterator = localMap.keySet().iterator();
            for (int i = 0; i < len; i++) {
                if (!schema.columnName(i).equals(localColumnIterator.next())) {
                    /* The order or name of columns is different and
                    thus cannot write to this csv file. return false.
                     */
                    return false;
                }
            }
        }

        csvWriter.writeValue(generator, localMap);
        localMap.clear();
        return true;
    }

    public Map<String, Object> convertRecord(GenericRecord record) {
        map.clear();
        Schema schema = record.getSchema();
        for (Field field : schema.getFields()) {
            convertAvro(record.get(field.pos()), field.schema(), field.name());
        }
        return map;
    }

    private void convertAvro(Object data, Schema schema, String prefix) {
        switch (schema.getType()) {
            case RECORD: {
                GenericRecord record = (GenericRecord) data;
                Schema subSchema = record.getSchema();
                for (Field field : subSchema.getFields()) {
                    Object subData = record.get(field.pos());
                    convertAvro(subData, field.schema(), prefix + '.' + field.name());
                }
                break;
            }
            case MAP: {
                Schema valueType = schema.getValueType();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>)data).entrySet()) {
                    String name = prefix + '.' + entry.getKey();
                    convertAvro(entry.getValue(), valueType, name);
                }
                break;
            }
            case ARRAY: {
                Schema itemType = schema.getElementType();
                int i = 0;
                for (Object orig : (List<?>)data) {
                    convertAvro(orig, itemType, prefix + '.' + i);
                    i++;
                }
                break;
            }
            case UNION: {
                int type = new GenericData().resolveUnion(schema, data);
                convertAvro(data, schema.getTypes().get(type), prefix);
                break;
            }
            case BYTES:
                map.put(prefix, ((ByteBuffer)data).array());
                break;
            case FIXED:
                map.put(prefix, ((GenericFixed)data).bytes());
                break;
            case ENUM:
            case STRING:
                map.put(prefix, data.toString());
                break;
            case INT:
            case LONG:
            case DOUBLE:
            case FLOAT:
            case BOOLEAN:
            case NULL:
                map.put(prefix, data);
                break;
            default:
                throw new IllegalArgumentException("Cannot parse field type " + schema.getType());
        }
    }


    @Override
    public void close() throws IOException {
        generator.close();
    }

    @Override
    public void flush() throws IOException {
        generator.flush();
    }
}
