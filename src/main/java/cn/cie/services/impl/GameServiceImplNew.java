package cn.cie.services.impl;

import cn.cie.entity.Game;
import cn.cie.entity.Kind;
import cn.cie.entity.Tag;
import cn.cie.entity.dto.GameDTO;
import cn.cie.mapper.*;
import cn.cie.services.GameService;
import cn.cie.utils.MsgCenter;
import cn.cie.utils.RedisUtil;
import cn.cie.utils.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.*;

/**
 * Created by lh2 on 2023/6/8.
 * Fixed by Copilot on 2023/5/25.
 */
@Service
public class GameServiceImplNew implements GameService {

    @Autowired
    private GameMapper gameMapper;
    @Autowired
    private KindMapper kindMapper;
    @Autowired
    private KindmapperMapper kindmapperMapper;
    @Autowired
    private TagmapperMapper tagmapperMapper;
    @Autowired
    private TagMapper tagMapper;
    @Autowired
    private ImgMapper imgMapper;
    @Autowired
    private RedisUtil<GameDTO> redisUtil;

    public Result<GameDTO> getById(Integer id) {
        Game game = gameMapper.selectById(id);
        if (game == null) {
            return Result.fail(MsgCenter.ERROR_PARAMS);
        }
        List<Integer> tagIds = tagmapperMapper.selectByGame(id);
        List<Tag> tags = null;
        if (tagIds.size() != 0) {
            tags = tagMapper.selectByIds(tagIds);
        }
        List<String> img = imgMapper.selectByGame(game.getId());
        GameDTO res = new GameDTO(game, tags, img);
        return Result.success(res);
    }

    public Result<List<GameDTO>> getRandomGames() {
        // 先从缓存中取数据，如果没有再自动生成
        List<GameDTO> res = redisUtil.lall("everyday", GameDTO.class);
        
        // 检查是否需要修复图片数据
        boolean needsImageFix = false;
        if (res != null && !res.isEmpty()) {
            for (int i = 0; i < res.size(); i++) {
                GameDTO game = res.get(i);
                System.out.println(game.toString());
                
                // 检查图片是否为空，如果为空则重新加载
                if (game.getImg() == null || game.getImg().isEmpty()) {
                    // 从数据库重新加载图片
                    List<String> images = imgMapper.selectByGame(game.getId());
                    game.setImg(images);
                    needsImageFix = true;
                    System.out.println("Fixed images for game: " + game.getId() + ", image count: " + images.size());
                }
            }
            
            // 如果有修复图片，更新缓存
            if (needsImageFix) {
                System.out.println("Images were fixed, updating cache...");
                redisUtil.delete(RedisUtil.EVERYDAY);
                int tmp = 1000 * 3600 * 24;
                long zero = (System.currentTimeMillis() / tmp * tmp + tmp - TimeZone.getDefault().getRawOffset()) / 1000;
                redisUtil.rpushObjectExAtTime(RedisUtil.EVERYDAY, GameDTO.class, zero, res.toArray());
                System.out.println("Cache updated with fixed images");
            }
        }
        
        // 如果缓存为空，创建新数据
        if (res == null || res.isEmpty()) {
            List<Game> allgames = gameMapper.selectByStat(Game.STAT_OK);
            int count = allgames.size();
            Set<Integer> numSet = new HashSet<Integer>();
            Random random = new Random();
            List<Game> games = new ArrayList<Game>();
            // 如果游戏数量大于5个就随机取5个，否则取全部的
            if (count > 5) {
                while (numSet.size() < 5) {
                    numSet.add(random.nextInt(count));
                }
                Iterator<Integer> i = numSet.iterator();
                while (i.hasNext()) {
                    games.add(allgames.get(i.next()));
                }
            } else {
                games = allgames;
            }
            res = paresGameDTO(games);
            System.out.println("res:"+res.toArray().toString());
            // 将数据存入缓存中
            int tmp = 1000 * 3600 * 24;
            long zero = (System.currentTimeMillis() / tmp * tmp + tmp - TimeZone.getDefault().getRawOffset()) / 1000;    //明天零点零分零秒的unix时间戳
            redisUtil.rpushObjectExAtTime(RedisUtil.EVERYDAY, GameDTO.class, zero, res.toArray());
            System.out.println(redisUtil.lall("everyday", GameDTO.class).size());
            System.out.println("数据进入缓存");
        }
        return Result.success(res);
    }

    public Result<List<GameDTO>> newestGames() {
        List<GameDTO> res = redisUtil.lall(RedisUtil.NEWESTGAME, GameDTO.class);
        // 检查并修复图片
        boolean needsImageFix = checkAndFixImages(res);
        if (needsImageFix) {
            redisUtil.delete(RedisUtil.NEWESTGAME);
            redisUtil.rpushObjectEx(RedisUtil.NEWESTGAME, GameDTO.class, 60 * 10, res.toArray());
        }
        
        if (res == null || res.size() == 0) {
            List<Game> games = gameMapper.selectByStatOrderByDate(Game.STAT_OK);
            res = paresGameDTO(games);
            redisUtil.rpushObjectEx(RedisUtil.NEWESTGAME, GameDTO.class, 60 * 10, res.toArray());
        }
        return Result.success(res);
    }

    public Result<List<GameDTO>> preUpGames() {
        List<GameDTO> res = redisUtil.lall(RedisUtil.PRE_UP_GAMES, GameDTO.class);
        // 检查并修复图片
        boolean needsImageFix = checkAndFixImages(res);
        if (needsImageFix) {
            redisUtil.delete(RedisUtil.PRE_UP_GAMES);
            redisUtil.rpushObjectEx(RedisUtil.PRE_UP_GAMES, GameDTO.class, 60 * 10, res.toArray());
        }
        
        if (res == null || res.size() == 0) {
            List<Game> games = gameMapper.selectByStatOrderByDate(Game.STAT_PRE);
            res = paresGameDTO(games);
            redisUtil.rpushObjectEx(RedisUtil.PRE_UP_GAMES, GameDTO.class, 60 * 10, res.toArray());
        }
        return Result.success(res);
    }

    public Result<List<GameDTO>> search(String info) {
        List<Integer> kindIds = kindMapper.selectIdByLikeName(info);
        List<Integer> tagIds = tagMapper.selectIdByLikeName(info);
        List<Integer> gameIdsOfKind = null;
        if (kindIds != null && kindIds.size() > 0) {
            gameIdsOfKind = kindmapperMapper.selectBatchByKinds(kindIds);
        }
        List<Integer> gameIdsOfTag = null;
        if (tagIds != null && tagIds.size() > 0) {
            gameIdsOfTag = tagmapperMapper.selectBatchByTags(tagIds);
        }
        Set<Integer> tmpGameIds = new HashSet<Integer>();
        if (gameIdsOfKind != null && gameIdsOfKind.size() > 0) {
            tmpGameIds.addAll(gameIdsOfKind);
        }
        if (gameIdsOfTag != null && gameIdsOfTag.size() > 0) {
            tmpGameIds.addAll(gameIdsOfTag);
        }
        List<Game> games = null;
        if (tmpGameIds.size() > 0) {
            List<Integer> gameIds = new ArrayList<Integer>(tmpGameIds);
            games = gameMapper.selectByIdsAndInfo(gameIds, info);
        } else {
            games = gameMapper.selectByInfo(info);
        }
        return Result.success(paresGameDTO(games));
    }

    public Result<List<GameDTO>> getFreeGames() {
        List<Game> games = gameMapper.selectFreeGames();
        return Result.success(paresGameDTO(games));
    }

    public boolean exists(Integer id) {
        return gameMapper.selectById(id) != null;
    }
    
    /**
     * 检查并修复游戏DTO中的图片数据
     * 
     * @param games 游戏DTO列表
     * @return 如果有任何修复，则返回true
     */
    private boolean checkAndFixImages(List<GameDTO> games) {
        boolean needsImageFix = false;
        if (games != null && !games.isEmpty()) {
            for (GameDTO game : games) {
                if (game.getImg() == null || game.getImg().isEmpty()) {
                    List<String> images = imgMapper.selectByGame(game.getId());
                    game.setImg(images);
                    needsImageFix = true;
                    System.out.println("Fixed images for game: " + game.getId() + ", image count: " + images.size());
                }
            }
        }
        return needsImageFix;
    }

    private List<GameDTO> paresGameDTO(List<Game> games) {
        List<GameDTO> gameDTOS = new ArrayList<GameDTO>();
        for (Game game : games) {
            List<String> img = imgMapper.selectByGame(game.getId());                // 获取所有的图片
            if (img != null) {
                System.out.println("Game " + game.getId() + " has " + img.size() + " images");
            }
            GameDTO dto = new GameDTO(game, null, img);
            gameDTOS.add(dto);
        }
        return gameDTOS;
    }
}
