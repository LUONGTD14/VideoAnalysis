#ifndef MP4_PARSER_H
#define MP4_PARSER_H

#include "common.h"

inline std::string parseMp4BoxStream(BufferReader& reader, off_t endOffset) {
    std::string json = "[";
    bool first = true;

    while (reader.tell() < endOffset) {
        off_t startOffset = reader.tell();
        uint32_t size = reader.readU32();
        std::string type = reader.readString(4);

        if (size == 0) break;

        off_t headerSize = 8;
        if (size == 1) {
            size = reader.readU64();
            headerSize += 8;
        }

        off_t payloadOffset = startOffset + headerSize;
        off_t payloadSize = size - headerSize;

        if (payloadOffset + payloadSize > endOffset) {
            payloadSize = endOffset - payloadOffset;
        }

        bool isContainer = (type == "moov" || type == "trak" || type == "mdia" ||
                            type == "minf" || type == "stbl" || type == "dinf" ||
                            type == "moof" || type == "traf" || type == "udta");

        if (!first) json += ",";
        first = false;

        json += "{";
        json += "\"name\":\"" + type + "\",";
        json += "\"offset\":" + std::to_string(startOffset) + ",";
        json += "\"size\":" + std::to_string(size) + ",";
        json += "\"payload_offset\":" + std::to_string(payloadOffset) + ",";
        json += "\"payload_size\":" + std::to_string(payloadSize) + ",";
        json += "\"is_container\":" + std::string(isContainer ? "true" : "false");

        if (type == "mvhd" && payloadSize >= 28) {
            reader.seek(payloadOffset);
            uint8_t version = reader.readU8();
            reader.skip(3);
            
            uint32_t timescale = 0;
            uint64_t duration = 0;
            off_t tsOffset = 0;
            off_t durOffset = 0;
            std::string tsFmt = ">I";
            std::string durFmt = ">I";
            std::string tsType = "uint32";
            std::string durType = "uint32";
            
            if (version == 1) {
                reader.skip(16);
                tsOffset = reader.tell();
                timescale = reader.readU32();
                durOffset = reader.tell();
                duration = reader.readU64();
                durFmt = ">Q";
                durType = "uint64";
            } else {
                reader.skip(8);
                tsOffset = reader.tell();
                timescale = reader.readU32();
                durOffset = reader.tell();
                duration = reader.readU32();
            }
            
            json += ",\"fields\":{";
            json += "\"timescale\":" + std::to_string(timescale) + ",";
            json += "\"duration\":" + std::to_string(duration);
            json += "}";
            
            json += ",\"editable_fields\":{";
            json += "\"timescale\":{\"offset\":" + std::to_string(tsOffset) + ",\"format\":\"" + tsFmt + "\",\"value\":" + std::to_string(timescale) + ",\"label\":\"Timescale\",\"type\":\"" + tsType + "\"},";
            json += "\"duration\":{\"offset\":" + std::to_string(durOffset) + ",\"format\":\"" + durFmt + "\",\"value\":" + std::to_string(duration) + ",\"label\":\"Duration\",\"type\":\"" + durType + "\"}";
            json += "}";
        }
        else if (type == "tkhd" && payloadSize >= 32) {
            reader.seek(payloadOffset);
            uint8_t version = reader.readU8();
            reader.skip(3);
            
            uint32_t trackId = 0;
            uint64_t duration = 0;
            off_t idOffset = 0;
            off_t durOffset = 0;
            off_t wOffset = 0;
            off_t hOffset = 0;
            
            std::string durFmt = ">I";
            std::string durType = "uint32";
            
            if (version == 1) {
                reader.skip(16);
                idOffset = reader.tell();
                trackId = reader.readU32();
                reader.skip(4);
                durOffset = reader.tell();
                duration = reader.readU64();
                durFmt = ">Q";
                durType = "uint64";
            } else {
                reader.skip(8);
                idOffset = reader.tell();
                trackId = reader.readU32();
                reader.skip(4);
                durOffset = reader.tell();
                duration = reader.readU32();
            }
            
            reader.seek(payloadOffset + (version == 1 ? 84 : 76));
            wOffset = reader.tell();
            double width = reader.readU32() / 65536.0;
            hOffset = reader.tell();
            double height = reader.readU32() / 65536.0;
            
            json += ",\"fields\":{";
            json += "\"track_id\":" + std::to_string(trackId) + ",";
            json += "\"duration\":" + std::to_string(duration) + ",";
            json += "\"width\":" + std::to_string(width) + ",";
            json += "\"height\":" + std::to_string(height);
            json += "}";
            
            json += ",\"editable_fields\":{";
            json += "\"track_id\":{\"offset\":" + std::to_string(idOffset) + ",\"format\":\">I\",\"value\":" + std::to_string(trackId) + ",\"label\":\"Track ID\",\"type\":\"uint32\"},";
            json += "\"duration\":{\"offset\":" + std::to_string(durOffset) + ",\"format\":\"" + durFmt + "\",\"value\":" + std::to_string(duration) + ",\"label\":\"Duration\",\"type\":\"" + durType + "\"},";
            json += "\"width\":{\"offset\":" + std::to_string(wOffset) + ",\"format\":\">I\",\"value\":" + std::to_string(width) + ",\"label\":\"Width\",\"type\":\"fixed16_16\"},";
            json += "\"height\":{\"offset\":" + std::to_string(hOffset) + ",\"format\":\">I\",\"value\":" + std::to_string(height) + ",\"label\":\"Height\",\"type\":\"fixed16_16\"}";
            json += "}";
        }
        else if (isContainer) {
            reader.seek(payloadOffset);
            json += ",\"children\":" + parseMp4BoxStream(reader, payloadOffset + payloadSize);
        }

        json += "}";
        reader.seek(startOffset + size);
    }

    json += "]";
    return json;
}

#endif // MP4_PARSER_H
