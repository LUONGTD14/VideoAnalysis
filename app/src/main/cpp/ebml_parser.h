#ifndef EBML_PARSER_H
#define EBML_PARSER_H

#include "common.h"

inline std::string parseEbmlStream(BufferReader& reader, off_t endOffset) {
    std::string json = "[";
    bool first = true;

    while (reader.tell() < endOffset) {
        off_t startOffset = reader.tell();
        
        auto idPair = readVint(reader, true);
        int64_t elId = idPair.first;
        int idLen = idPair.second;
        if (elId == -1) break;

        auto sizePair = readVint(reader, false);
        int64_t elSize = sizePair.first;
        int sizeLen = sizePair.second;

        off_t headerSize = idLen + sizeLen;
        off_t payloadOffset = startOffset + headerSize;
        off_t payloadSize = (elSize == -1) ? (endOffset - payloadOffset) : elSize;

        std::string name = "Element_0x" + std::to_string(elId);
        std::string type = "binary";
        bool isContainer = false;

        if (elId == 0x1A45DFA3) { name = "EBML"; type = "master"; isContainer = true; }
        else if (elId == 0x4282) { name = "DocType"; type = "string"; }
        else if (elId == 0x18538067) { name = "Segment"; type = "master"; isContainer = true; }
        else if (elId == 0x1549A966) { name = "Info"; type = "master"; isContainer = true; }
        else if (elId == 0x4489) { name = "Duration"; type = "float"; }
        else if (elId == 0x1654AE6B) { name = "Tracks"; type = "master"; isContainer = true; }
        else if (elId == 0xAE) { name = "TrackEntry"; type = "master"; isContainer = true; }
        else if (elId == 0xD7) { name = "TrackNumber"; type = "uint"; }
        else if (elId == 0x83) { name = "TrackType"; type = "uint"; }
        else if (elId == 0x86) { name = "CodecID"; type = "string"; }
        else if (elId == 0xE0) { name = "Video"; type = "master"; isContainer = true; }
        else if (elId == 0xB0) { name = "PixelWidth"; type = "uint"; }
        else if (elId == 0xBA) { name = "PixelHeight"; type = "uint"; }
        else if (elId == 0xE1) { name = "Audio"; type = "master"; isContainer = true; }
        else if (elId == 0x9F) { name = "Channels"; type = "uint"; }
        else if (elId == 0xB5) { name = "SamplingFrequency"; type = "float"; }
        else if (elId == 0x1F43B675) { name = "Cluster"; type = "master"; isContainer = true; }

        if (!first) json += ",";
        first = false;

        json += "{";
        json += "\"name\":\"" + name + "\",";
        json += "\"offset\":" + std::to_string(startOffset) + ",";
        json += "\"size\":" + std::to_string(headerSize + payloadSize) + ",";
        json += "\"payload_offset\":" + std::to_string(payloadOffset) + ",";
        json += "\"payload_size\":" + std::to_string(payloadSize) + ",";
        json += "\"is_container\":" + std::string(isContainer ? "true" : "false");

        if (isContainer) {
            reader.seek(payloadOffset);
            json += ",\"children\":" + parseEbmlStream(reader, payloadOffset + payloadSize);
        } else if (payloadSize > 0) {
            reader.seek(payloadOffset);
            json += ",\"fields\":{";
            
            if (type == "uint") {
                uint64_t val = 0;
                for (int i = 0; i < payloadSize; i++) {
                    val = (val << 8) | reader.readU8();
                }
                json += "\"" + name + "\":" + std::to_string(val);
                
                std::string fmt = "";
                if (payloadSize == 1) fmt = ">B";
                else if (payloadSize == 2) fmt = ">H";
                else if (payloadSize == 4) fmt = ">I";
                else if (payloadSize == 8) fmt = ">Q";
                
                if (!fmt.empty()) {
                    json += "},\"editable_fields\":{";
                    json += "\"" + name + "\":{\"offset\":" + std::to_string(payloadOffset) + ",\"format\":\"" + fmt + "\",\"value\":" + std::to_string(val) + ",\"label\":\"" + name + "\",\"type\":\"uint\"}";
                }
            }
            else if (type == "float") {
                double val = 0.0;
                if (payloadSize == 4) {
                    uint32_t temp = reader.readU32();
                    float fVal;
                    std::memcpy(&fVal, &temp, 4);
                    val = fVal;
                } else if (payloadSize == 8) {
                    uint64_t temp = reader.readU64();
                    std::memcpy(&val, &temp, 8);
                }
                json += "\"" + name + "\":" + std::to_string(val);
                
                std::string fmt = (payloadSize == 4) ? ">f" : ">d";
                json += "},\"editable_fields\":{";
                json += "\"" + name + "\":{\"offset\":" + std::to_string(payloadOffset) + ",\"format\":\"" + fmt + "\",\"value\":" + std::to_string(val) + ",\"label\":\"" + name + "\",\"type\":\"float\"}";
            }
            else if (type == "string" || type == "utf8") {
                std::string val = reader.readString(payloadSize);
                json += "\"" + name + "\":\"" + escapeJson(val) + "\"";
            }
            else {
                json += "\"" + name + "\":\"Binary data\"";
            }
            
            json += "}";
        }

        json += "}";
        reader.seek(startOffset + headerSize + payloadSize);
    }

    json += "]";
    return json;
}

#endif // EBML_PARSER_H
