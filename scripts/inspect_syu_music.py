import zipfile, struct

with zipfile.ZipFile(r'C:\Users\UserAnonimo\.gemini\antigravity\brain\448c89a3-4b33-4c3a-bb05-f5d6beb17e88\syu_music.apk') as z:
    dex = z.read('classes.dex')
    string_ids_size = struct.unpack_from('<I', dex, 0x38)[0]
    string_ids_off = struct.unpack_from('<I', dex, 0x3c)[0]
    def get_str(sid):
        if sid >= string_ids_size: return ""
        off = struct.unpack_from('<I', dex, string_ids_off + sid * 4)[0]
        p = off
        while (dex[p] & 0x80) != 0: p += 1
        p += 1
        end = dex.find(b'\x00', p)
        return dex[p:end].decode('latin1', errors='ignore')

    # Find methods near 1054842
    # In dex, method calls are invoke-virtual (0x6e..0x72), invoke-direct, etc.
    # Method IDs are at method_ids_off
    method_ids_off = struct.unpack_from('<I', dex, 0x5c)[0]
    method_ids_size = struct.unpack_from('<I', dex, 0x58)[0]
    
    def get_method_name(mid):
        if mid >= method_ids_size: return ""
        # method_id_item: class_idx (ushort), proto_idx (ushort), name_idx (uint)
        name_idx = struct.unpack_from('<I', dex, method_ids_off + mid * 8 + 4)[0]
        return get_str(name_idx)

    p = 1054830
    while p < 1055200:
        op = dex[p]
        if op == 0x1a:
            sid = struct.unpack_from('<H', dex, p+2)[0]
            print(f'{p}: const-string "{get_str(sid)}"')
            p += 4
        elif op in [0x6e, 0x6f, 0x70, 0x71, 0x72]: # invoke-virtual/direct/static/super/interface
            mid = struct.unpack_from('<H', dex, p+2)[0]
            print(f'{p}: invoke -> {get_method_name(mid)}')
            p += 6
        else:
            p += 2

