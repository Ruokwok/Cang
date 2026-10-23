# JSON 解析、序列化与对象映射

> 返回 [文档索引](../README.md)

## 要点

- `cang/util/json/`：纯 Cang 实现，不依赖反射
- 严格 RFC 8259：不支持注释、尾逗号、单引号字符串、`NaN` / `Infinity`
- `Json.parse` → `JsonValue` 树；`Json.stringify` / `Json.pretty` 输出紧凑或两空格缩进文本
- `JsonObject` / `JsonArray` 是有序容器，支持链式构造
- `JsonNumber` 保留解析时的原始文本，超大整数序列化不丢精度
- 对象映射采用手写 `JsonAdapter`，通过 `JsonMapper` 按 `Class` 注册；嵌套对象由 `JsonContext` 递归处理
- 解析错误抛 `JsonException`，带字节偏移 `offset` 与行列号 `line` / `column`

## 快速开始

```cang
import cang/util/json/Json
import cang/util/json/JsonObject
import cang/util/json/JsonValue

class Main
    static func void main(String[] args) {
        JsonValue root = Json.parse("{\"name\":\"Cang\",\"nums\":[1,2,3]}")

        JsonObject o = JsonObject!$(root)
        Stdout.println(o.getString("name"))      # Cang

        JsonArray nums = o.getArray("nums")
        Stdout.println(nums.get(1).asInt())      # 2

        String compact = Json.stringify(root)
        Stdout.println(compact)                  # {"name":"Cang","nums":[1,2,3]}

        Stdout.println(Json.pretty(root))
    }
```

`Json`、`JsonValue`、`JsonObject` 等类型被引用时会自动按 `cang/util/json/` 查找；显式 `import` 同样有效，且更清晰。

## 解析入口

```cang
JsonValue v = Json.parse(text)        # 任意 JSON 值，尾部只允许空白
JsonObject o = Json.parseObject(text) # 根节点必须是对象，否则抛 JsonException
JsonArray a = Json.parseArray(text)   # 根节点必须是数组
```

解析器按 UTF-8 字节扫描，支持 `\uXXXX` 转义与 UTF-16 代理对；字符串中未转义的控制字符、孤立代理项、非法转义都会报错。默认最大嵌套 512 层，可通过 `new JsonParser(text, maxDepth)` 调整。

## JsonValue 树

所有节点继承 `JsonValue`：

| 方法 | 说明 |
|------|------|
| `type()` | 返回 `JsonValue.TYPE_NULL/BOOL/NUMBER/STRING/ARRAY/OBJECT` |
| `typeName()` | 可读类型名：`null`、`bool`、`number`、`string`、`array`、`object` |
| `isNull()` / `isBool()` / `isNumber()` / `isString()` / `isArray()` / `isObject()` | 类型判断 |
| `asBool()` / `asLong()` / `asInt()` / `asDouble()` / `asString()` | 取值；类型不符抛 `JsonException` |
| `get(String)` / `get(int)` | 对象按键、数组按下标取值；越界或键不存在抛错 |
| `size()` | 容器元素个数；标量返回 1 |
| `keys()` | 对象键快照（插入顺序） |

### JsonObject

```cang
JsonObject o = new JsonObject()
o.put("name", "Cang")
o.put("age", 3)
o.put("active", true)
o.putNull("deleted")
o.put("tags", 1).putNull("extra")   # put 返回自身，可链式
```

- `put` 提供 `String` / `int` / `long` / `double` / `bool` / `JsonValue` 重载；同键覆盖并保留首次插入位置；
- `putValue(key, value)` 是非重载入口，参数静态类型明确时优先使用；
- `get` 不存在抛错；`getOrNull` 不存在返回 `null`（注意 JSON `null` 节点是 `JsonNull` 实例，不是 `null`）；`getOrDefault(key, fallback)`；
- 便捷读取：`getString` / `getBool` / `getInt` / `getLong` / `getDouble` / `getObject` / `getArray`；
- `has` / `remove` / `clear` / `keys` / `toArray`。

### JsonArray

```cang
JsonArray a = new JsonArray()
a.add(1)
a.add("two")
a.add(true)
a.addNull()

Stdout.println(a.size())        # 4
JsonValue first = a.get(0)
a.set(0, first)
a.remove(3)
```

- `add` 提供 `String` / `int` / `long` / `double` / `bool` / `JsonValue` 重载；`addValue` 为非重载入口；
- `get` / `set` / `remove` 都做下标检查；`toArray()` 返回长度等于 `size()` 的快照数组。

### JsonNumber

```cang
JsonNumber n = JsonNumber!$(Json.parse("1.25e2"))
Stdout.println(n.asDouble())   # 125.0
Stdout.println(n.rawText())    # 1.25e2

JsonValue huge = Json.parse("123456789012345678901234567890")
Stdout.println(Json.stringify(huge)) # 原始文本，不丢精度
```

- 解析时保留 `raw` 文本，writer 优先原样输出；
- 在 `long` 范围内的整数 `isIntegral()` 为 `true`；超出范围退化为 `double`，但 `raw` 仍保证序列化精度；
- 非有限 `double`（NaN/Infinity）序列化为 `null`，因为标准 JSON 不允许这些值。

## 构造与序列化

除 `new` 外，`Json` 提供便捷工厂：

```cang
JsonObject o = Json.object()
JsonArray a = Json.array()
JsonString s = Json.text("x")
JsonNumber i = Json.integer(42)
JsonNumber l = Json.integer(42L)
JsonNumber d = Json.decimal(1.5)
JsonBool b = Json.boolValue(true)
JsonNull z = Json.nullValue()
```

输出：

```cang
String compact = Json.stringify(value)          # 紧凑
String pretty = Json.stringify(value, true)     # 两空格缩进
String pretty2 = Json.pretty(value)             # 同上
```

字符串会转义 `"`、`\`、`\b`、`\f`、`\n`、`\r`、`\t` 以及其他 `0x00..0x1F` 控制字符；非 ASCII UTF-8 字节原样保留。

## 对象映射（手工 Adapter）

不使用反射。每个业务类实现一个 `JsonAdapter`：

```cang
# Address.cang
class Address
    String city = "";
    String street = "";

    init(String city, String street) {
        this.city = city
        this.street = street
    }
```

```cang
# AddressAdapter.cang
import cang/util/json/JsonAdapter
import cang/util/json/JsonContext
import cang/util/json/JsonObject
import cang/util/json/JsonValue
import demo/Address

class AddressAdapter : JsonAdapter
    func JsonValue encode(JsonContext ctx, Object value) {
        Address a = Address!$(value)
        JsonObject o = new JsonObject()
        o.put("city", a.city)
        o.put("street", a.street)
        JsonValue out = o
        return out
    }

    func Object decode(JsonContext ctx, JsonValue value) {
        JsonObject o = JsonObject!$(value)
        Address a = new Address(o.getString("city"), o.getString("street"))
        Object out = a
        return out
    }
```

嵌套对象通过 `ctx` 递归编解码：

```cang
class UserAdapter : JsonAdapter
    func JsonValue encode(JsonContext ctx, Object value) {
        User u = User!$(value)
        JsonObject o = new JsonObject()
        o.put("name", u.name)
        o.put("age", u.age)
        o.put("address", ctx.toJsonValue(u.address))
        JsonValue out = o
        return out
    }

    func Object decode(JsonContext ctx, JsonValue value) {
        JsonObject o = JsonObject!$(value)
        Address address = Address!$(ctx.fromJsonValue(o.get("address"), Address.class))
        User u = new User(o.getString("name"), o.getInt("age"), address)
        Object out = u
        return out
    }
```

注册并使用：

```cang
JsonMapper mapper = new JsonMapper()
JsonAdapter addressAdapter = new AddressAdapter()
JsonAdapter userAdapter = new UserAdapter()
mapper.register(Address.class, addressAdapter)
mapper.register(User.class, userAdapter)

Object userObject = user
String text = mapper.toJson(userObject)
String pretty = mapper.toJsonPretty(userObject)

Object decoded = mapper.fromJson(text, User.class)
User back = User!$(decoded)
```

`JsonMapper` 还提供：

| 方法 | 说明 |
|------|------|
| `register(Class, JsonAdapter)` | 按 `Class` 注册，类型名取自 `Class.getName()` |
| `registerName(String, JsonAdapter)` | 按全名注册（命名空间用 `_` 连接，如 `demo_User`） |
| `registerFor(Object sample, JsonAdapter)` | 用示例对象的 `getClassName()` 注册 |
| `find(String)` / `findFor(Object)` / `contains(String)` / `size()` | 查询注册表 |
| `toJson(Object)` / `toJsonPretty(Object)` | 编码为紧凑/缩进 JSON |
| `fromJson(String, Class)` / `fromJsonByName(String, String)` | 解码为对象 |
| `toJsonValue(Object)` / `fromJsonValue(JsonValue, Class)` | 供 adapter 嵌套调用 |

### JsonSerializable

只需要编码、不需要解码时，可以让类实现 `JsonSerializable`：

```cang
class Point : JsonSerializable
    int x = 0;
    int y = 0;

    func JsonValue toJson() {
        JsonObject o = new JsonObject()
        o.put("x", this.x)
        o.put("y", this.y)
        JsonValue out = o
        return out
    }
```

```cang
Point p = new Point(1, 2)
JsonSerializable serializable = p
String text = Json.stringifySerializable(serializable)
```

## 错误处理

```cang
try {
    Json.parse("{bad}")
} catch (JsonException e) {
    Stdout.println(e.message)
    Stdout.println(e.line)      # 1 起
    Stdout.println(e.column)    # 1 起，按字节计数
    Stdout.println(e.offset)    # UTF-8 字节偏移
}
```

`JsonException` 继承 `Error`，因此 `catch (Error e)` 也能捕获。类型访问错误（例如对字符串调用 `asInt()`）同样抛 `JsonException`。

## 注意事项

- 当前编译器在“子类指针传给基类参数 / 作为基类返回”时不会自动转换。构造完 `JsonObject` / `JsonArray` 后，若要当作 `JsonValue` 使用，先赋给 `JsonValue` 局部变量，例如 `JsonValue out = o`；调用 `Json.stringify(o)` 时同理先赋给 `JsonValue`。
- `JsonMapper` 注册表是可变状态，不是线程安全的；跨线程共享时需要自行加锁。
- `JsonParser` / `JsonWriter` 都是一次性工作对象，不要并发复用同一个实例。
- 本模块不做反射，字段名与 JSON 键的映射完全由 adapter 决定；字段改名只需同步修改 adapter。
- 解析器不校验 UTF-8 字节序列本身是否合法（Cang `String` 以字节为单位），但会拒绝未转义控制字符、非法转义和孤立代理项。
- 由于 Cang 字符串以 NUL 结尾，`\u0000` 转义会被拒绝；需要二进制数据时请改用其他编码（如 Base64 字符串）。
