# Object 根类

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Object`：**所有类的隐式根类**——未写 `: Parent` 的类自动继承 `Object`
- 对象头第一个字段是类型 ID（`i32`），供 `like` 与动态分派在运行时判型
- 继承链最终都汇聚到 `Object`，因此 `like Object` 对任意对象成立
- 语言机制详解见 [继承与多态](../14-继承与多态.md)

## 自动继承

```cang
class Point(int x = 0, int y = 0)
# 等价于
class Point(int x = 0, int y = 0) : Object()
```

编译器在收集阶段为未声明父类的类补上 `superClass = "Object"`，无需手写。

## 根类型判断

```cang
class Main()
var p = new Point(1, 2)
if (p like Point) {
    Stdout.println("is Point")
}
if (p like Object) {
    Stdout.println("any object is an Object")   # 总是成立
}
```

- `like` 读取对象头类型 ID，沿继承链判断是否为目标类或其子类；
- `Object` 的类型 ID 是全部 ID 的根（`assignTypeIds` 从它开始分配）。

## 继承中的 Object

```cang
class Animal(String name = "animal")
class Dog(String name = "dog") : Animal(name)

class Main()
var d = new Dog("rex")
Stdout.println(d.name)        # rex（父类字段经父类构造初始化）
if (d like Animal) {
    Stdout.println("Dog is an Animal")   # 继承链命中
}
```

## 注意

- Cang 是**单继承**，不支持多继承；
- 与 Java 不同：Cang 类没有从 Object 继承来的通用 `toString()/equals()/hashCode()` 方法可用——相等用 `==`（`String` 内容比较、对象引用比较），字符串化用字符串拼接或 `Stdout.println`；
- `final` 类不可被继承（见 [继承与多态](../14-继承与多态.md)）。
